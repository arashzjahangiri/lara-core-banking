package io.lara.ledger.domain;

/**
 * The five classes of the accounting equation, and the side each one normally sits on.
 *
 * <pre>
 *     ASSET  =  LIABILITY  +  EQUITY  +  (INCOME - EXPENSE)
 * </pre>
 *
 * <p>This is the distinction between a ledger and a table of numbers that go up and down. A
 * customer's deposit is not the bank's money — it is money the bank <em>owes</em>, so it is a
 * {@link #LIABILITY} on the bank's own balance sheet and a credit balance. The cash the bank holds
 * against it is an {@link #ASSET}. Every transfer keeps the equation true.
 *
 * <p>The normal side determines whether a posting raises or lowers a balance. A debit increases an
 * asset and decreases a liability; a credit does the reverse. Getting this backwards is how a
 * ledger ends up reporting a customer's savings as a debt they owe.
 */
public enum AccountClass {

    /** What the bank owns or is owed: cash at the central bank, loans it has made. */
    ASSET(EntrySide.DEBIT),

    /** What the bank owes: customer deposits, accrued interest payable. */
    LIABILITY(EntrySide.CREDIT),

    /** The residual: share capital and retained earnings. */
    EQUITY(EntrySide.CREDIT),

    /** What the bank earns: fees, interest received. */
    INCOME(EntrySide.CREDIT),

    /** What the bank spends: interest paid, scheme charges. */
    EXPENSE(EntrySide.DEBIT);

    private final EntrySide normalSide;

    AccountClass(EntrySide normalSide) {
        this.normalSide = normalSide;
    }

    /** The side on which a positive balance of this class sits. */
    public EntrySide normalSide() {
        return normalSide;
    }

    /** Whether a posting on {@code side} raises the balance of an account of this class. */
    public boolean isIncreasedBy(EntrySide side) {
        return normalSide == side;
    }

    /**
     * {@code +1} when a posting on {@code side} raises the balance, {@code -1} when it lowers it.
     * Balance derivation folds postings with this.
     */
    public int effectOf(EntrySide side) {
        return isIncreasedBy(side) ? 1 : -1;
    }
}
