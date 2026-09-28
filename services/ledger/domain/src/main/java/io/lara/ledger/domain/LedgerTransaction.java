package io.lara.ledger.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * A balanced set of postings, written as one indivisible unit.
 *
 * <p>The invariant is that <strong>the legs sum to zero</strong> — total debits equal total
 * credits — and it is enforced in the constructor, so an unbalanced transaction cannot be
 * constructed, let alone persisted. Nothing downstream needs to re-check it.
 *
 * <p>There is no fixed pair of legs. Two is the minimum, not the rule. A transfer carrying a fee is
 * four legs, and an FX transaction more, so the real invariant is the sum rather than the count:
 *
 * <pre>
 *   DEBIT   CUSTOMER000001   EUR 100.00     the sender
 *   CREDIT  CUSTOMER000002   EUR  99.50     the receiver
 *   CREDIT  BANK.FEE.INCOME.EUR EUR 0.50    the bank's fee
 * </pre>
 *
 * <p>Transactions are immutable and append-only. A mistake is corrected by posting a reversing
 * transaction built from {@link #contra(TransactionId, Instant)}, never by editing or deleting
 * what was written — which is what makes the ledger an audit record rather than a cache of the
 * current state.
 */
public record LedgerTransaction(TransactionId id,
        TransactionReference reference,
        Instant occurredAt,
        List<PostingLeg> legs) {

    private static final int MINIMUM_LEGS = 2;

    public LedgerTransaction {
        Objects.requireNonNull(id, "transaction id must not be null");
        Objects.requireNonNull(reference, "transaction reference must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(legs, "legs must not be null");

        legs = List.copyOf(legs);

        if (legs.size() < MINIMUM_LEGS) {
            throw new IllegalArgumentException(
                    "a transaction needs at least " + MINIMUM_LEGS + " legs, had " + legs.size());
        }
        requireSingleCurrency(legs);
        requireBalanced(legs);
    }

    public static LedgerTransaction of(TransactionId id,
            TransactionReference reference,
            Instant occurredAt,
            PostingLeg... legs) {

        return new LedgerTransaction(id, reference, occurredAt, List.of(legs));
    }

    /** Whether this transaction carries the same legs, in the same order, as {@code other}. */
    public boolean hasSameLegsAs(LedgerTransaction other) {
        Objects.requireNonNull(other, "other must not be null");
        return legs.equals(other.legs);
    }

    /** The currency every leg is denominated in. */
    public Currency currency() {
        return legs.get(0).amount().currency();
    }

    public Money totalDebits() {
        return total(EntrySide.DEBIT);
    }

    public Money totalCredits() {
        return total(EntrySide.CREDIT);
    }

    /** Every leg touching {@code account}. More than one is normal: a fee debits the sender twice. */
    public List<PostingLeg> legsFor(AccountId account) {
        Objects.requireNonNull(account, "account must not be null");
        return legs.stream().filter(leg -> leg.account().equals(account)).toList();
    }

    /**
     * A transaction that exactly undoes this one, with every leg on the opposite side. Used to
     * correct a mistake and to compensate a failed saga step.
     */
    public LedgerTransaction contra(TransactionId reversalId,
            TransactionReference reversalReference,
            Instant reversedAt) {

        return new LedgerTransaction(reversalId, reversalReference, reversedAt,
                legs.stream().map(PostingLeg::contra).toList());
    }

    private Money total(EntrySide side) {
        return legs.stream()
                .filter(leg -> leg.side() == side)
                .map(PostingLeg::amount)
                .reduce(Money::plus)
                .orElseGet(() -> Money.zero(currency()));
    }

    private static void requireSingleCurrency(List<PostingLeg> legs) {
        Currency currency = legs.get(0).amount().currency();
        for (PostingLeg leg : legs) {
            if (!leg.amount().currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "every leg must share one currency, found " + currency.getCurrencyCode()
                                + " and " + leg.amount().currency().getCurrencyCode());
            }
        }
    }

    private static void requireBalanced(List<PostingLeg> legs) {
        Currency currency = legs.get(0).amount().currency();
        Money debits = Money.zero(currency);
        Money credits = Money.zero(currency);
        for (PostingLeg leg : legs) {
            if (leg.isDebit()) {
                debits = debits.plus(leg.amount());
            } else {
                credits = credits.plus(leg.amount());
            }
        }
        if (!debits.equals(credits)) {
            throw new IllegalArgumentException(
                    "transaction does not balance: debits " + debits + " but credits " + credits);
        }
    }
}
