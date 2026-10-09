package io.lara.payments.application;

import java.time.LocalDate;
import java.util.Objects;

import io.lara.payments.domain.Money;
import io.lara.payments.domain.TransferReference;

/**
 * An instruction to move money, in business terms rather than in entries.
 *
 * <p>Deliberately not a list of legs. Which accounts are debited and credited, and in what order,
 * is the ledger's model and not payments' — building the entries here would put double-entry
 * knowledge in a service that ADR-0003 says must never decide a balance. The adapter turns this
 * into legs; this names what is supposed to happen.
 *
 * <p>The fee is separate from the amount because they go to different places: the creditor
 * receives the amount, the debtor pays both, and the difference is the bank's income.
 */
public record PostingCommand(
        TransferReference reference,
        String debtorLedgerAccount,
        String creditorLedgerAccount,
        Money amount,
        Money fee,
        String feeIncomeAccount,
        LocalDate valueDate) {

    public PostingCommand {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(debtorLedgerAccount, "debtor ledger account must not be null");
        Objects.requireNonNull(creditorLedgerAccount, "creditor ledger account must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(fee, "fee must not be null");
        Objects.requireNonNull(feeIncomeAccount, "fee income account must not be null");
        Objects.requireNonNull(valueDate, "value date must not be null");

        if (debtorLedgerAccount.equals(creditorLedgerAccount)) {
            throw new IllegalArgumentException(
                    "a posting cannot debit and credit the same account: " + debtorLedgerAccount);
        }
    }
}