package io.lara.ledger.application;

import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.Money;

import java.util.Currency;

/**
 * A posting the ledger refused, with the reason expressed as a type rather than a message.
 *
 * <p>Sealed, so the REST layer can map every case to a status code in an exhaustive switch that
 * stops compiling if a new reason is added and left unhandled. A message-only exception pushes
 * that decision into string matching, which fails silently.
 *
 * <p>These are deliberately distinct from the {@link IllegalArgumentException}s the domain throws.
 * The domain rejects what is <em>impossible</em> — a transaction that does not balance. These
 * reject what is merely <em>not allowed</em>, which depends on state and configuration the domain
 * cannot see.
 */
public abstract sealed class PostingRejectedException extends RuntimeException
        permits PostingRejectedException.UnknownAccount,
        PostingRejectedException.AccountCurrencyMismatch,
        PostingRejectedException.PostingLimitExceeded,
        PostingRejectedException.ReferenceReused {

    private PostingRejectedException(String message) {
        super(message);
    }

    /**
     * The reference has already been used, for a different set of legs.
     *
     * <p>A retry carries identical legs and is answered with the original transaction. Different
     * legs under the same reference is a caller bug — two distinct movements sharing one key — and
     * returning the original would hide it while quietly dropping the second movement.
     */
    public static final class ReferenceReused extends PostingRejectedException {

        private final transient io.lara.ledger.domain.TransactionReference reference;

        ReferenceReused(io.lara.ledger.domain.TransactionReference reference) {
            super("reference " + reference + " was already used for a different transaction");
            this.reference = reference;
        }

        public io.lara.ledger.domain.TransactionReference reference() {
            return reference;
        }
    }

    /** A leg referenced an account that is not open. */
    public static final class UnknownAccount extends PostingRejectedException {

        private final transient AccountId account;

        UnknownAccount(AccountId account) {
            super("no such account: " + account);
            this.account = account;
        }

        public AccountId account() {
            return account;
        }
    }

    /** A leg was denominated in a currency the account cannot hold. */
    public static final class AccountCurrencyMismatch extends PostingRejectedException {

        private final transient AccountId account;
        private final transient Currency expected;
        private final transient Currency actual;

        AccountCurrencyMismatch(AccountId account, Currency expected, Currency actual) {
            super("account " + account + " is denominated in " + expected.getCurrencyCode()
                    + " but the leg was " + actual.getCurrencyCode());
            this.account = account;
            this.expected = expected;
            this.actual = actual;
        }

        public AccountId account() {
            return account;
        }

        public Currency expected() {
            return expected;
        }

        public Currency actual() {
            return actual;
        }
    }

    /** The transaction moved more than the configured limit for its currency. */
    public static final class PostingLimitExceeded extends PostingRejectedException {

        private final transient Money attempted;
        private final transient Money limit;

        PostingLimitExceeded(Money attempted, Money limit) {
            super("posting of " + attempted + " exceeds the limit of " + limit);
            this.attempted = attempted;
            this.limit = limit;
        }

        public Money attempted() {
            return attempted;
        }

        public Money limit() {
            return limit;
        }
    }
}
