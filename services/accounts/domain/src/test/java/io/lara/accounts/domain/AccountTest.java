package io.lara.accounts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AccountTest {

    private static final Iban IBAN = Iban.of("DK5000400440116243");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final CustomerId OWNER = CustomerId.newId();

    private static Account opening() {
        return Account.opening(IBAN, OWNER, "CUSTOMER000001", EUR);
    }

    @Nested
    @DisplayName("an account")
    class AnAccount {

        @Test
        void starts_in_opening_and_cannot_yet_move_money() {
            Account account = opening();

            assertThat(account.status()).isEqualTo(AccountStatus.OPENING);
            assertThat(account.permitsMovement()).isFalse();
        }

        @Test
        void carries_its_owner_currency_and_ledger_account() {
            Account account = opening();

            assertThat(account.iban()).isEqualTo(IBAN);
            assertThat(account.customer()).isEqualTo(OWNER);
            assertThat(account.currency()).isEqualTo(EUR);
            assertThat(account.ledgerAccountId()).isEqualTo("CUSTOMER000001");
        }

        /** Identity lives here; money lives in the ledger. There is no balance on this type. */
        @Test
        void holds_no_balance() {
            assertThat(Account.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("balance", "amount");
        }

        @Test
        void rejects_missing_parts() {
            assertThatThrownBy(() -> new Account(null, OWNER, "L1", EUR, AccountStatus.OPEN))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Account(IBAN, null, "L1", EUR, AccountStatus.OPEN))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Account(IBAN, OWNER, null, EUR, AccountStatus.OPEN))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Account(IBAN, OWNER, "L1", null, AccountStatus.OPEN))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Account(IBAN, OWNER, "  ", EUR, AccountStatus.OPEN))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the life of an account")
    class Lifecycle {

        @Test
        void opens_then_becomes_usable() {
            Account account = opening().activate();

            assertThat(account.status()).isEqualTo(AccountStatus.OPEN);
            assertThat(account.permitsMovement()).isTrue();
        }

        @Test
        void freezes_and_unfreezes() {
            Account frozen = opening().activate().freeze();

            assertThat(frozen.status()).isEqualTo(AccountStatus.FROZEN);
            assertThat(frozen.permitsMovement()).as("money must not move while frozen").isFalse();
            assertThat(frozen.activate().status()).isEqualTo(AccountStatus.OPEN);
        }

        /**
         * The transition that matters most. A closed account's IBAN may have been reissued, its
         * balance settled and its closure reported — reopening it would resurrect all of that
         * silently. Restoring banking means a new account, which leaves both facts on record.
         */
        @Test
        void a_closed_account_never_reopens() {
            Account closed = opening().activate().close();

            assertThat(closed.status().isTerminal()).isTrue();
            assertThatThrownBy(closed::activate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CLOSED");
            assertThatThrownBy(closed::freeze).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(closed::close).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void can_be_closed_before_it_was_ever_usable() {
            assertThat(opening().close().status()).isEqualTo(AccountStatus.CLOSED);
        }

        @Test
        void cannot_be_frozen_before_it_is_open() {
            assertThatThrownBy(() -> opening().freeze())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OPENING");
        }

        /** Transitions return a new account; nothing is mutated in place. */
        @Test
        void leaves_the_previous_state_untouched() {
            Account before = opening();
            Account after = before.activate();

            assertThat(before.status()).isEqualTo(AccountStatus.OPENING);
            assertThat(after).isNotSameAs(before);
        }

        @ParameterizedTest
        @EnumSource(AccountStatus.class)
        void only_an_open_account_permits_movement(AccountStatus status) {
            assertThat(status.permitsMovement()).isEqualTo(status == AccountStatus.OPEN);
        }

        @ParameterizedTest
        @EnumSource(AccountStatus.class)
        void nothing_moves_out_of_closed(AccountStatus target) {
            assertThat(AccountStatus.CLOSED.canMoveTo(target)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(AccountStatus.class)
        void no_status_moves_to_itself(AccountStatus status) {
            assertThat(status.canMoveTo(status)).isFalse();
        }
    }

    @Nested
    @DisplayName("customers")
    class Customers {

        @Test
        void start_unverified_and_cannot_hold_a_usable_account() {
            Customer customer = Customer.unverified("Arash Zand Jahangiri");

            assertThat(customer.kyc()).isEqualTo(Customer.KycStatus.PENDING);
            assertThat(customer.mayHoldAnOpenAccount()).isFalse();
        }

        @Test
        void may_hold_an_account_once_verified() {
            assertThat(Customer.unverified("A Name").verified().mayHoldAnOpenAccount()).isTrue();
        }

        @Test
        void a_rejected_customer_may_not() {
            assertThat(Customer.unverified("A Name").rejected().mayHoldAnOpenAccount()).isFalse();
        }

        @Test
        void keeps_its_identity_across_a_status_change() {
            Customer before = Customer.unverified("A Name");

            assertThat(before.verified().id()).isEqualTo(before.id());
            assertThat(before.kyc()).as("the original is untouched").isEqualTo(Customer.KycStatus.PENDING);
        }

        @Test
        void trims_and_rejects_an_empty_name() {
            assertThat(Customer.unverified("  Arash  ").name()).isEqualTo("Arash");
            assertThatThrownBy(() -> Customer.unverified("   "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Customer.unverified("A".repeat(201)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
