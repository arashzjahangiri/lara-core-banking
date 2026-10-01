package io.lara.accounts.application;

import java.util.Objects;

import io.lara.accounts.domain.CustomerId;
import io.lara.accounts.domain.Iban;

/**
 * A request the accounts service refused, typed rather than described in a message.
 *
 * <p>Sealed, so the REST layer maps every case in an exhaustive switch that stops compiling when
 * a new reason appears. The same pattern as the ledger's, for the same reason.
 */
public abstract sealed class AccountRejectedException extends RuntimeException
        permits AccountRejectedException.UnknownCustomer,
        AccountRejectedException.UnknownAccount,
        AccountRejectedException.CustomerNotVerified,
        AccountRejectedException.LedgerAccountTaken {

    private AccountRejectedException(String message) {
        super(message);
    }

    public static final class UnknownCustomer extends AccountRejectedException {

        private final transient CustomerId id;

        UnknownCustomer(CustomerId id) {
            super("no such customer: " + id);
            this.id = Objects.requireNonNull(id);
        }

        public CustomerId id() {
            return id;
        }
    }

    public static final class UnknownAccount extends AccountRejectedException {

        private final transient Iban iban;

        UnknownAccount(Iban iban) {
            super("no such account: " + iban);
            this.iban = Objects.requireNonNull(iban);
        }

        public Iban iban() {
            return iban;
        }
    }

    /** Identity checks are not complete, so no account of theirs may become usable. */
    public static final class CustomerNotVerified extends AccountRejectedException {

        private final transient CustomerId id;

        CustomerNotVerified(CustomerId id) {
            super("customer " + id + " is not verified and cannot hold an open account");
            this.id = Objects.requireNonNull(id);
        }

        public CustomerId id() {
            return id;
        }
    }

    /** Two accounts pointing at one ledger account would both claim the same money. */
    public static final class LedgerAccountTaken extends AccountRejectedException {

        private final transient String ledgerAccountId;

        LedgerAccountTaken(String ledgerAccountId) {
            super("ledger account " + ledgerAccountId + " already belongs to another account");
            this.ledgerAccountId = Objects.requireNonNull(ledgerAccountId);
        }

        public String ledgerAccountId() {
            return ledgerAccountId;
        }
    }
}
