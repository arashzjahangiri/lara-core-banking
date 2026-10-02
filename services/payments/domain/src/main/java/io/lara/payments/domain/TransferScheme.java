package io.lara.payments.domain;

/**
 * How a transfer actually gets where it is going.
 *
 * <p>The distinction is not cosmetic. Moving money between two accounts in this bank is a pair of
 * ledger entries and nothing else — nobody outside is involved, so it costs nothing and settles
 * the moment it is booked. Moving money to another bank hands it to a settlement system that runs
 * on its own calendar and closes at its own cutoff, which is where fees and value dates come from.
 *
 * <p>Treating the two as one operation is how a bank quotes a same-day value date on a Friday
 * evening and then settles on the Tuesday.
 */
public enum TransferScheme {

    /**
     * Both accounts are at this bank. A book transfer: two entries, no external system, no fee,
     * and it settles the day it is made regardless of what the settlement calendar says.
     */
    INTERNAL,

    /**
     * The creditor is at another bank in the euro area. Settles through TARGET2, so it carries a
     * fee and a cutoff, and its value date is a business day rather than today.
     */
    SEPA_CREDIT_TRANSFER
}
