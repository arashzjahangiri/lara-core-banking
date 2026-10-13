package io.lara.payments.fakes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.lara.payments.application.LedgerPosting;
import io.lara.payments.application.LedgerRefusedException;
import io.lara.payments.application.PostingCommand;
import io.lara.payments.domain.LedgerTransactionRef;

/**
 * A ledger that actually keeps books.
 *
 * <p>The point of this class is that a concurrency test against a permissive stub proves nothing.
 * If the fake accepts every posting, then "no transfer overdrew the account" is a statement about
 * the fake rather than about the orchestrator, and the test would pass just as happily with the
 * locking removed.
 *
 * <p>So this enforces the three things the real ledger enforces, and the tests assert against
 * them:
 *
 * <ol>
 *   <li><strong>Entries balance.</strong> Debits and credits for one transaction must sum to
 *       zero, so the trial balance across every account is zero at all times.</li>
 *   <li><strong>Funds exist.</strong> A customer account cannot go below zero, so a double spend
 *       is refused here exactly as it would be refused by the real ledger.</li>
 *   <li><strong>References deduplicate.</strong> Posting twice under one reference returns the
 *       transaction already recorded, which is ADR-0007's guarantee and the thing that makes
 *       re-driving a saga step safe.</li>
 * </ol>
 *
 * <p>What it is <em>not</em> is a substitute for running the real ledger. It cannot be — it has no
 * chart of accounts, no hash chain and no persistence. It stands in for the ledger's contract so
 * that payments' own concurrency can be tested at speed; the cross-service run belongs to the
 * compose stack.
 *
 * <p>Synchronised rather than lock-free, deliberately. A fake with a subtle race of its own would
 * produce failures that look like bugs in the code under test, and debugging the test harness
 * instead of the system is the worst way to spend an afternoon.
 */
// No @Priority. A @Priority-annotated alternative is enabled application-wide, which would
// replace the real adapter in every test — including ResilienceTest, whose entire subject is
// what happens when the real adapter cannot reach anything. Enabled per-profile instead, by
// StubbedDependenciesProfile#getEnabledAlternatives.
@Alternative
@ApplicationScoped
public class InMemoryLedger implements LedgerPosting {

    private final Map<String, Long> balances = new HashMap<>();
    private final Map<String, LedgerTransactionRef> byReference = new ConcurrentHashMap<>();
    private final Map<LedgerTransactionRef, List<Leg>> postings = new ConcurrentHashMap<>();
    private final Map<LedgerTransactionRef, LedgerTransactionRef> reversals = new ConcurrentHashMap<>();
    private final AtomicInteger attempted = new AtomicInteger();

    /** One side of an entry. Negative is a debit, positive a credit, so a balanced set sums to zero. */
    private record Leg(String account, long signedMinorUnits) {
    }

    /** Puts money into an account without a counterparty, to set a test up. */
    public synchronized void fund(String account, long minorUnits) {
        balances.merge(account, minorUnits, Long::sum);
    }

    public synchronized long balanceOf(String account) {
        return balances.getOrDefault(account, 0L);
    }

    /**
     * The sum of every account.
     *
     * <p>Zero at all times, except for whatever {@link #fund} put in without a counterparty. A
     * non-zero movement away from that opening figure means money was created or destroyed.
     */
    public synchronized long trialBalance() {
        return balances.values().stream().mapToLong(Long::longValue).sum();
    }

    public int postingsAttempted() {
        return attempted.get();
    }

    public int transactionsRecorded() {
        return postings.size();
    }

    public int reversalsRecorded() {
        return reversals.size();
    }

    /** How many distinct transactions this reference produced. Must never exceed one. */
    public long transactionsFor(String reference) {
        return byReference.containsKey(reference) ? 1 : 0;
    }

    @Override
    public LedgerTransactionRef post(PostingCommand command) {
        attempted.incrementAndGet();
        String reference = command.reference().value();

        synchronized (this) {
            // ADR-0007: one reference, one transaction. Checked before anything moves, so a
            // re-driven saga step finds the posting it already made instead of making another.
            LedgerTransactionRef existing = byReference.get(reference);
            if (existing != null) {
                return existing;
            }

            long amount = command.amount().minorUnits();
            long fee = command.fee().minorUnits();
            long totalDebit = amount + fee;

            long available = balances.getOrDefault(command.debtorLedgerAccount(), 0L);
            if (available < totalDebit) {
                throw new LedgerRefusedException("insufficient funds in "
                        + command.debtorLedgerAccount() + ": have " + available
                        + ", need " + totalDebit);
            }

            List<Leg> legs = fee == 0
                    ? List.of(
                            new Leg(command.debtorLedgerAccount(), -totalDebit),
                            new Leg(command.creditorLedgerAccount(), amount))
                    : List.of(
                            new Leg(command.debtorLedgerAccount(), -totalDebit),
                            new Leg(command.creditorLedgerAccount(), amount),
                            new Leg(command.feeIncomeAccount(), fee));

            requireBalanced(legs, reference);
            apply(legs);

            LedgerTransactionRef recorded = LedgerTransactionRef.of(UUID.randomUUID());
            byReference.put(reference, recorded);
            postings.put(recorded, legs);
            return recorded;
        }
    }

    /**
     * Reverses by posting the mirror image, never by editing anything.
     *
     * <p>ADR-0011's rule, and the reason the trial balance still holds afterwards: a reversal is
     * another balanced entry rather than a deletion, so the books never go through an
     * inconsistent state.
     */
    @Override
    public synchronized LedgerTransactionRef reverse(LedgerTransactionRef original, String reason) {
        LedgerTransactionRef existing = reversals.get(original);
        if (existing != null) {
            return existing;
        }

        List<Leg> originalLegs = postings.get(original);
        if (originalLegs == null) {
            throw new LedgerRefusedException("no such transaction to reverse: " + original);
        }

        List<Leg> contra = originalLegs.stream()
                .map(leg -> new Leg(leg.account(), -leg.signedMinorUnits()))
                .toList();

        requireBalanced(contra, "reversal of " + original);
        apply(contra);

        LedgerTransactionRef recorded = LedgerTransactionRef.of(UUID.randomUUID());
        reversals.put(original, recorded);
        postings.put(recorded, contra);
        return recorded;
    }

    private void apply(List<Leg> legs) {
        for (Leg leg : legs) {
            balances.merge(leg.account(), leg.signedMinorUnits(), Long::sum);
        }
    }

    /**
     * Refuses an entry that does not sum to zero.
     *
     * <p>Not defensive padding. If the adapter ever builds legs wrongly — a fee charged to the
     * creditor, a total that forgets it — this is what turns that into a failed test rather than
     * a trial balance that silently drifts.
     */
    private static void requireBalanced(List<Leg> legs, String what) {
        long sum = legs.stream().mapToLong(Leg::signedMinorUnits).sum();
        if (sum != 0) {
            throw new LedgerRefusedException(
                    "entry for " + what + " does not balance: legs sum to " + sum);
        }
    }
}
