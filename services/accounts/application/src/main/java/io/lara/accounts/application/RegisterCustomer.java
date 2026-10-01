package io.lara.accounts.application;

import java.util.Objects;

import io.lara.accounts.domain.Customer;
import io.lara.accounts.domain.CustomerId;

/** Registers customers and records the outcome of identity checks. */
public final class RegisterCustomer {

    private final AccountDirectory directory;

    public RegisterCustomer(AccountDirectory directory) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
    }

    public Customer register(String name) {
        Customer customer = Customer.unverified(name);
        directory.saveCustomer(customer);
        return customer;
    }

    public Customer verify(CustomerId id) {
        return update(id, Customer::verified);
    }

    public Customer reject(CustomerId id) {
        return update(id, Customer::rejected);
    }

    private Customer update(CustomerId id, java.util.function.UnaryOperator<Customer> change) {
        Objects.requireNonNull(id, "customer id must not be null");

        Customer customer = directory.findCustomer(id)
                .orElseThrow(() -> new AccountRejectedException.UnknownCustomer(id));

        Customer updated = change.apply(customer);
        directory.saveCustomer(updated);
        return updated;
    }
}
