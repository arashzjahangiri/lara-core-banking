package io.lara.ledger.application;

import java.util.Optional;

import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerAccount;

/**
 * A port: what the application needs to know about accounts, expressed as the application sees it.
 *
 * <p>Declared here rather than in the infrastructure module, and deliberately narrow. The use case
 * needs to resolve an id to an account and nothing more, so that is all this offers. A wider
 * interface would make the adapter harder to fake and would invite callers to depend on capability
 * they do not use.
 *
 * <p>The implementation lives in {@code infrastructure} and is supplied by a producer in
 * {@code bootstrap}. Nothing in this module knows it is backed by a database.
 */
public interface LedgerAccounts {

    /** The account with this id, or empty when no such account is open. */
    Optional<LedgerAccount> findById(AccountId id);
}
