package io.lara.ledger.domain;

/**
 * Which side of the ledger a posting falls on.
 *
 * <p>Debit and credit are not synonyms for increase and decrease. Whether a posting raises or
 * lowers a balance depends on the account it lands on: a debit increases an asset but decreases a
 * liability. {@link AccountClass} holds that rule.
 *
 * <p>A transaction balances when its debits and its credits are equal, which is the invariant the
 * whole ledger rests on.
 */
public enum EntrySide {

    DEBIT,
    CREDIT;

    public EntrySide opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
