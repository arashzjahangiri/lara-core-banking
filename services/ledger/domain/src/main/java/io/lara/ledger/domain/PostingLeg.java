package io.lara.ledger.domain;

import java.util.Objects;

/**
 * One side of one movement: an account, whether it is debited or credited, and how much.
 *
 * <p>The amount is always positive. Direction lives in the {@link EntrySide} rather than in the
 * sign, which is how an accountant reads a ledger and removes the ambiguity of a negative debit.
 * {@link #signedAmount()} applies the sign when arithmetic needs it.
 *
 * <p>A leg names an {@link AccountId} rather than holding a {@link LedgerAccount}. Posting does not
 * need to know an account's class — only balance derivation does — and keeping the reference thin
 * means a transaction can be validated without loading anything.
 */
public record PostingLeg(AccountId account, EntrySide side, Money amount) {

    public PostingLeg {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "a posting leg must move a positive amount, was " + amount);
        }
    }

    public static PostingLeg debit(AccountId account, Money amount) {
        return new PostingLeg(account, EntrySide.DEBIT, amount);
    }

    public static PostingLeg credit(AccountId account, Money amount) {
        return new PostingLeg(account, EntrySide.CREDIT, amount);
    }

    public static PostingLeg debit(String account, Money amount) {
        return debit(AccountId.of(account), amount);
    }

    public static PostingLeg credit(String account, Money amount) {
        return credit(AccountId.of(account), amount);
    }

    public boolean isDebit() {
        return side == EntrySide.DEBIT;
    }

    public boolean isCredit() {
        return side == EntrySide.CREDIT;
    }

    /** Positive for a debit, negative for a credit. The legs of a transaction sum to zero. */
    public Money signedAmount() {
        return isDebit() ? amount : amount.negated();
    }

    /**
     * The same movement on the other side, used to reverse a posting. Reversal is always a new
     * contra entry: a posting is never updated or deleted once written.
     */
    public PostingLeg contra() {
        return new PostingLeg(account, side.opposite(), amount);
    }

    @Override
    public String toString() {
        return side + " " + account + " " + amount;
    }
}
