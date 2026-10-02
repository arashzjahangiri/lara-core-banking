package io.lara.payments.domain;

/**
 * Why a transfer was refused.
 *
 * <p>A closed set rather than free text, because this value reaches a customer. "Rejected" with a
 * stack trace behind it is useless to the person asking what happened to their money, and a
 * support agent cannot filter on a sentence.
 *
 * <p>Every one of these means the same thing about the money: none of it moved. A failure that
 * happens after the posting is not a rejection and cannot be expressed here — that path ends in
 * {@code COMPENSATED}, with the reversal recorded.
 */
public enum RejectionReason {

    /** The debtor or the creditor is not an account this bank can find. */
    UNKNOWN_ACCOUNT,

    /** The account exists but is closed, frozen or not yet verified. */
    ACCOUNT_NOT_ACTIVE,

    /** The debtor does not hold enough to cover the amount and the fee. */
    INSUFFICIENT_FUNDS,

    /** Risk said no: a limit was exceeded, or the beneficiary matched the sanctions list. */
    SCREENING_BLOCKED,

    /** A second person looked at it and declined. */
    APPROVAL_REFUSED,

    /** The ledger refused the posting outright — an unbalanced or otherwise invalid entry. */
    LEDGER_REFUSED
}