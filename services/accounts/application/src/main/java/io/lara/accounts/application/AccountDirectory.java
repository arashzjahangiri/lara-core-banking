package io.lara.accounts.application;

import java.util.List;
import java.util.Optional;

import io.lara.accounts.domain.Account;
import io.lara.accounts.domain.Customer;
import io.lara.accounts.domain.CustomerId;
import io.lara.accounts.domain.Iban;

/** What the application needs to know about customers and their accounts. */
public interface AccountDirectory {

    Optional<Customer> findCustomer(CustomerId id);

    Optional<Account> findAccount(Iban iban);

    List<Account> accountsOf(CustomerId customer);

    /** Whether this ledger account is already claimed, so two accounts cannot share one. */
    boolean isLedgerAccountTaken(String ledgerAccountId);

    void saveCustomer(Customer customer);

    void saveAccount(Account account);
}
