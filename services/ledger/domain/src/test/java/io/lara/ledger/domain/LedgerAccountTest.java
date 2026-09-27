package io.lara.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class LedgerAccountTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Nested
    @DisplayName("account id")
    class Id {

        @Test
        void accepts_dotted_uppercase_segments() {
            assertThat(AccountId.of("BANK.FEE.INCOME.EUR").value()).isEqualTo("BANK.FEE.INCOME.EUR");
            assertThat(AccountId.of("CUSTOMER000123").value()).isEqualTo("CUSTOMER000123");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "lowercase", "WITH SPACE", "TRAILING.", ".LEADING", "DOUBLE..DOT",
                "WITH-HYPHEN", "WITH_UNDERSCORE", "WITH/SLASH", ""
        })
        void rejects_anything_that_would_need_escaping(String candidate) {
            assertThatThrownBy(() -> AccountId.of(candidate)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejects_null() {
            assertThatThrownBy(() -> AccountId.of(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejects_an_id_longer_than_the_column() {
            String tooLong = "A".repeat(65);
            assertThatThrownBy(() -> AccountId.of(tooLong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("64");
        }

        /** Distinct types stop an account id being passed where a transaction reference was meant. */
        @Test
        void is_a_value_that_sorts_and_compares() {
            assertThat(AccountId.of("A")).isLessThan(AccountId.of("B"));
            assertThat(AccountId.of("BANK.CASH.EUR")).isEqualTo(AccountId.of("BANK.CASH.EUR"));
            assertThat(AccountId.of("BANK.CASH.EUR")).hasToString("BANK.CASH.EUR");
        }
    }

    @Nested
    @DisplayName("account")
    class Account {

        @Test
        void carries_its_class_and_currency() {
            LedgerAccount account = LedgerAccount.of("CUSTOMER000001", AccountClass.LIABILITY, "EUR");

            assertThat(account.id()).isEqualTo(AccountId.of("CUSTOMER000001"));
            assertThat(account.accountClass()).isEqualTo(AccountClass.LIABILITY);
            assertThat(account.currency()).isEqualTo(EUR);
        }

        @Test
        void a_customer_deposit_is_a_credit_balance_because_the_bank_owes_it() {
            LedgerAccount deposit = LedgerAccount.of("CUSTOMER000001", AccountClass.LIABILITY, "EUR");

            assertThat(deposit.normalSide()).isEqualTo(EntrySide.CREDIT);
            assertThat(deposit.isIncreasedBy(EntrySide.CREDIT)).isTrue();
            assertThat(deposit.isIncreasedBy(EntrySide.DEBIT)).isFalse();
        }

        @Test
        void starts_from_zero_in_its_own_currency() {
            LedgerAccount account = LedgerAccount.of("BANK.CASH.JPY", AccountClass.ASSET, "JPY");
            assertThat(account.zeroBalance()).isEqualTo(Money.zero("JPY"));
        }

        @Test
        void rejects_missing_parts() {
            AccountId id = AccountId.of("X");
            assertThatThrownBy(() -> new LedgerAccount(null, AccountClass.ASSET, EUR))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new LedgerAccount(id, null, EUR))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new LedgerAccount(id, AccountClass.ASSET, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejects_a_currency_that_cannot_denominate_money() {
            AccountId id = AccountId.of("X");
            Currency noCurrency = Currency.getInstance("XXX");
            assertThatThrownBy(() -> new LedgerAccount(id, AccountClass.ASSET, noCurrency))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XXX");
        }
    }

    @Nested
    @DisplayName("the bank's own accounts")
    class Bank {

        @Test
        void cash_is_an_asset_and_fee_income_is_income() {
            assertThat(SystemAccount.CASH.in("EUR").accountClass()).isEqualTo(AccountClass.ASSET);
            assertThat(SystemAccount.FEE_INCOME.in("EUR").accountClass()).isEqualTo(AccountClass.INCOME);
            assertThat(SystemAccount.CLEARING.in("EUR").accountClass()).isEqualTo(AccountClass.ASSET);
            assertThat(SystemAccount.SUSPENSE.in("EUR").accountClass()).isEqualTo(AccountClass.ASSET);
        }

        @Test
        void resolve_to_one_account_per_currency() {
            assertThat(SystemAccount.CASH.idIn(EUR)).isEqualTo(AccountId.of("BANK.CASH.EUR"));
            assertThat(SystemAccount.CASH.in("JPY").id()).isEqualTo(AccountId.of("BANK.CASH.JPY"));
            assertThat(SystemAccount.FEE_INCOME.idIn(EUR)).isEqualTo(AccountId.of("BANK.FEE.INCOME.EUR"));
        }

        @Test
        void the_same_account_in_two_currencies_is_two_accounts() {
            assertThat(SystemAccount.CASH.in("EUR")).isNotEqualTo(SystemAccount.CASH.in("USD"));
        }

        @ParameterizedTest
        @EnumSource(SystemAccount.class)
        void every_one_produces_a_valid_account_in_every_supported_currency(SystemAccount account) {
            for (String code : new String[] { "EUR", "USD", "JPY", "GBP" }) {
                LedgerAccount resolved = account.in(code);
                assertThat(resolved.currency().getCurrencyCode()).isEqualTo(code);
                assertThat(resolved.id().value()).endsWith("." + code);
                assertThat(resolved.accountClass()).isEqualTo(account.accountClass());
            }
        }
    }
}
