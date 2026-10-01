package io.lara.accounts.infrastructure.rest;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.lara.accounts.application.ViewAccounts;
import io.lara.accounts.domain.Account;
import io.lara.accounts.domain.Customer;

/**
 * Wire shapes. Validation annotations live here only; domain types validate themselves.
 */
final class AccountDtos {

    private AccountDtos() {
    }

    record RegisterCustomerRequest(@NotBlank @Size(max = 200) String name) {
    }

    record OpenAccountRequest(
            @NotBlank String customerId,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank @Size(max = 64) String ledgerAccountId) {
    }

    record CustomerResponse(String id, String name, String kyc) {

        static CustomerResponse of(Customer customer) {
            return new CustomerResponse(customer.id().toString(), customer.name(), customer.kyc().name());
        }
    }

    record AccountResponse(String iban, String ibanFormatted, String customerId,
            String ledgerAccountId, String currency, String status, boolean permitsMovement) {

        static AccountResponse of(Account account) {
            return new AccountResponse(
                    account.iban().value(),
                    account.iban().formatted(),
                    account.customer().toString(),
                    account.ledgerAccountId(),
                    account.currency().getCurrencyCode(),
                    account.status().name(),
                    account.permitsMovement());
        }
    }

    /**
     * A projected balance, with its age stated.
     *
     * <p>{@code asOf} and {@code staleSeconds} are not decoration. This number comes from a read
     * model fed asynchronously, and a client that cannot tell how old it is will assume it is
     * current — which is wrong exactly when it matters, while the consumer is behind.
     */
    record BalanceResponse(String iban, String currency, long minorUnits, BigDecimal amount,
            boolean overdrawn, String lastTransactionId, Instant asOf, long staleSeconds) {

        static BalanceResponse of(ViewAccounts.ProjectedBalance projected) {
            return new BalanceResponse(
                    projected.account().iban().value(),
                    projected.balance().amount().currency().getCurrencyCode(),
                    projected.balance().amount().minorUnits(),
                    projected.balance().amount().toDecimal(),
                    projected.balance().isOverdrawn(),
                    projected.balance().lastTransactionId(),
                    projected.balance().lastUpdated(),
                    projected.staleness().toSeconds());
        }
    }

    record ProblemResponse(String error, String detail) {
    }
}
