package io.lara.ledger.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerAccount;
import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.PostingLeg;
import io.lara.ledger.domain.TransactionId;

/**
 * Records a balanced transaction in the ledger.
 *
 * <p>Everything this class needs arrives through the constructor — the two ports, the limits and
 * the clock — so it can be built with {@code new} in a test with no container and no mocking
 * framework. There are no annotations on it. The one place it meets the framework is a producer in
 * the bootstrap module that calls this constructor.
 *
 * <p>The clock is injected for the same reason. A call to {@code Instant.now()} inside a use case
 * is a hidden dependency on the machine it runs on, and it makes any test about time either
 * impossible or flaky.
 *
 * <p>Validation is layered deliberately. The domain already guarantees a transaction balances and
 * uses one currency — those are impossible to violate because the object cannot be constructed
 * otherwise. This use case adds what the domain cannot see: whether the accounts exist, whether
 * they can hold the currency, and whether the amount is within policy.
 */
public final class PostTransaction {

    private final LedgerAccounts accounts;
    private final LedgerTransactions transactions;
    private final PostingLimits limits;
    private final Clock clock;

    public PostTransaction(LedgerAccounts accounts,
            LedgerTransactions transactions,
            PostingLimits limits,
            Clock clock) {

        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @return the transaction as it was recorded, including the identity and instant assigned to it
     * @throws PostingRejectedException when an account is unknown, cannot hold the currency, or the
     *     amount is over the configured limit
     * @throws IllegalArgumentException when the legs do not form a balanced transaction
     */
    public LedgerTransaction post(PostTransactionCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        List<PostingLeg> legs = command.legs();
        // Building the transaction first lets the domain reject anything impossible — unbalanced,
        // mixed-currency, too few legs — before any port is touched.
        Instant occurredAt = clock.instant();
        LedgerTransaction transaction =
                new LedgerTransaction(TransactionId.newId(), command.reference(), occurredAt, legs);

        Optional<LedgerTransaction> alreadyPosted = transactions.findByReference(command.reference());
        if (alreadyPosted.isPresent()) {
            return answerRetry(alreadyPosted.get(), transaction);
        }

        for (PostingLeg leg : legs) {
            requirePostable(leg);
        }
        limits.check(transaction.totalDebits());

        transactions.append(transaction);
        return transaction;
    }

    /**
     * A repeat of a reference already recorded. Identical legs mean a retry, which is answered with
     * the original and moves no money. Different legs mean the caller reused a key for a different
     * movement, which is refused rather than silently dropped.
     */
    private LedgerTransaction answerRetry(LedgerTransaction original, LedgerTransaction requested) {
        if (!original.hasSameLegsAs(requested)) {
            throw new PostingRejectedException.ReferenceReused(original.reference());
        }
        return original;
    }

    private void requirePostable(PostingLeg leg) {
        AccountId id = leg.account();
        LedgerAccount account = accounts.findById(id)
                .orElseThrow(() -> new PostingRejectedException.UnknownAccount(id));

        if (!account.currency().equals(leg.amount().currency())) {
            throw new PostingRejectedException.AccountCurrencyMismatch(
                    id, account.currency(), leg.amount().currency());
        }
    }
}
