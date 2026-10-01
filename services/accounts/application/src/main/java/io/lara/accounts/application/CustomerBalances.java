package io.lara.accounts.application;

import java.util.Optional;

import io.lara.accounts.domain.CustomerBalance;

/** Reads and writes the balance projection. */
public interface CustomerBalances {

    Optional<CustomerBalance> findByLedgerAccount(String ledgerAccountId);

    void save(CustomerBalance balance);
}
