package io.lara.ledger.domain;

import static io.lara.ledger.domain.PostingLeg.credit;
import static io.lara.ledger.domain.PostingLeg.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionHashTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final String SENDER = "CUSTOMER000001";
    private static final String RECEIVER = "CUSTOMER000002";

    private static Money eur(long minorUnits) {
        return Money.of(minorUnits, "EUR");
    }

    private static LedgerTransaction transfer(String reference, long minorUnits) {
        return LedgerTransaction.of(
                TransactionId.of("11111111-1111-1111-1111-111111111111"),
                TransactionReference.of(reference),
                NOW,
                debit(SENDER, eur(minorUnits)),
                credit(RECEIVER, eur(minorUnits)));
    }

    @Nested
    @DisplayName("hashing")
    class Hashing {

        @Test
        void is_deterministic_for_the_same_input() {
            LedgerTransaction transaction = transfer("PAYMENT-1", 10000);

            assertThat(TransactionHash.of(transaction, TransactionHash.GENESIS))
                    .isEqualTo(TransactionHash.of(transaction, TransactionHash.GENESIS));
        }

        @Test
        void produces_a_lowercase_sha256() {
            TransactionHash hash = TransactionHash.of(transfer("PAYMENT-1", 10000), TransactionHash.GENESIS);

            assertThat(hash.value()).hasSize(64).matches("[0-9a-f]{64}");
        }

        /** Chaining is the whole mechanism: the same transaction after a different one differs. */
        @Test
        void depends_on_the_previous_hash() {
            LedgerTransaction transaction = transfer("PAYMENT-1", 10000);
            TransactionHash other = TransactionHash.of(transfer("PAYMENT-0", 500), TransactionHash.GENESIS);

            assertThat(TransactionHash.of(transaction, TransactionHash.GENESIS))
                    .isNotEqualTo(TransactionHash.of(transaction, other));
        }

        @Test
        void changes_when_the_amount_changes() {
            assertThat(TransactionHash.of(transfer("PAYMENT-1", 10000), TransactionHash.GENESIS))
                    .isNotEqualTo(TransactionHash.of(transfer("PAYMENT-1", 10001), TransactionHash.GENESIS));
        }

        @Test
        void changes_when_the_reference_changes() {
            assertThat(TransactionHash.of(transfer("PAYMENT-1", 10000), TransactionHash.GENESIS))
                    .isNotEqualTo(TransactionHash.of(transfer("PAYMENT-2", 10000), TransactionHash.GENESIS));
        }

        @Test
        void changes_when_the_instant_changes() {
            LedgerTransaction later = LedgerTransaction.of(
                    TransactionId.of("11111111-1111-1111-1111-111111111111"),
                    TransactionReference.of("PAYMENT-1"),
                    NOW.plusSeconds(1),
                    debit(SENDER, eur(10000)),
                    credit(RECEIVER, eur(10000)));

            assertThat(TransactionHash.of(transfer("PAYMENT-1", 10000), TransactionHash.GENESIS))
                    .isNotEqualTo(TransactionHash.of(later, TransactionHash.GENESIS));
        }

        @Test
        void changes_when_a_leg_moves_to_a_different_account() {
            LedgerTransaction elsewhere = LedgerTransaction.of(
                    TransactionId.of("11111111-1111-1111-1111-111111111111"),
                    TransactionReference.of("PAYMENT-1"),
                    NOW,
                    debit(SENDER, eur(10000)),
                    credit("CUSTOMER000003", eur(10000)));

            assertThat(TransactionHash.of(transfer("PAYMENT-1", 10000), TransactionHash.GENESIS))
                    .isNotEqualTo(TransactionHash.of(elsewhere, TransactionHash.GENESIS));
        }

        /** Swapping which side is debited is a completely different movement. */
        @Test
        void changes_when_the_sides_are_swapped() {
            LedgerTransaction flipped = LedgerTransaction.of(
                    TransactionId.of("11111111-1111-1111-1111-111111111111"),
                    TransactionReference.of("PAYMENT-1"),
                    NOW,
                    credit(SENDER, eur(10000)),
                    debit(RECEIVER, eur(10000)));

            assertThat(TransactionHash.of(transfer("PAYMENT-1", 10000), TransactionHash.GENESIS))
                    .isNotEqualTo(TransactionHash.of(flipped, TransactionHash.GENESIS));
        }

        /**
         * Without a separator between fields, an account id ending in digits and an amount
         * beginning with them could run together and two different transactions could collide.
         */
        @Test
        void does_not_confuse_adjacent_fields() {
            LedgerTransaction one = LedgerTransaction.of(
                    TransactionId.of("11111111-1111-1111-1111-111111111111"),
                    TransactionReference.of("PAYMENT-1"), NOW,
                    debit("ACC1", eur(23)), credit("ACC2", eur(23)));
            LedgerTransaction two = LedgerTransaction.of(
                    TransactionId.of("11111111-1111-1111-1111-111111111111"),
                    TransactionReference.of("PAYMENT-1"), NOW,
                    debit("ACC12", eur(3)), credit("ACC2", eur(3)));

            assertThat(TransactionHash.of(one, TransactionHash.GENESIS))
                    .isNotEqualTo(TransactionHash.of(two, TransactionHash.GENESIS));
        }

        @Test
        void rejects_missing_arguments() {
            assertThatThrownBy(() -> TransactionHash.of(null, TransactionHash.GENESIS))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> TransactionHash.of(transfer("PAYMENT-1", 1), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("the chain")
    class Chain {

        /**
         * The property that makes tampering detectable: altering an early transaction invalidates
         * every hash after it, so a single edit cannot be hidden by fixing one row.
         */
        @Test
        void a_change_early_in_the_chain_invalidates_everything_after_it() {
            List<LedgerTransaction> book = List.of(
                    transfer("PAYMENT-1", 100),
                    transfer("PAYMENT-2", 200),
                    transfer("PAYMENT-3", 300));

            List<TransactionHash> original = chainOf(book);

            List<LedgerTransaction> tampered = new ArrayList<>(book);
            tampered.set(0, transfer("PAYMENT-1", 999));
            List<TransactionHash> after = chainOf(tampered);

            assertThat(after.get(0)).isNotEqualTo(original.get(0));
            assertThat(after.get(1)).isNotEqualTo(original.get(1));
            assertThat(after.get(2)).isNotEqualTo(original.get(2));
        }

        @Test
        void recomputing_an_untouched_chain_reproduces_it_exactly() {
            List<LedgerTransaction> book = List.of(
                    transfer("PAYMENT-1", 100),
                    transfer("PAYMENT-2", 200));

            assertThat(chainOf(book)).isEqualTo(chainOf(book));
        }

        @Test
        void the_first_transaction_links_to_genesis() {
            assertThat(TransactionHash.GENESIS.isGenesis()).isTrue();
            assertThat(TransactionHash.GENESIS.value()).isEqualTo("0".repeat(64));
            assertThat(TransactionHash.of(transfer("PAYMENT-1", 1), TransactionHash.GENESIS).isGenesis())
                    .isFalse();
        }

        private static List<TransactionHash> chainOf(List<LedgerTransaction> book) {
            List<TransactionHash> hashes = new ArrayList<>();
            TransactionHash previous = TransactionHash.GENESIS;
            for (LedgerTransaction transaction : book) {
                previous = TransactionHash.of(transaction, previous);
                hashes.add(previous);
            }
            return hashes;
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest
        @ValueSource(strings = { "", "abc", "ABCDEF", "g" })
        void rejects_anything_that_is_not_a_lowercase_sha256(String candidate) {
            assertThatThrownBy(() -> new TransactionHash(candidate))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejects_uppercase_hex_so_comparison_is_unambiguous() {
            assertThatThrownBy(() -> new TransactionHash("A".repeat(64)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lowercase");
        }

        @Test
        void rejects_null() {
            assertThatThrownBy(() -> new TransactionHash(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
