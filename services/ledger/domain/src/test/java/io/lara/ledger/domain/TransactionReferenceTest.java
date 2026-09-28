package io.lara.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionReferenceTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "PAYMENT-4417", "payment_4417", "a", "SEPA:2026-09-29:000123",
            "0", "ref.with.dots", "MiXeD-Case_123"
    })
    void accepts_the_shapes_a_caller_would_reasonably_use(String candidate) {
        assertThat(TransactionReference.of(candidate).value()).isEqualTo(candidate);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "-leading-hyphen", ".leading-dot", "with space",
            "with/slash", "with#hash", "with\ttab"
    })
    void rejects_anything_awkward_to_put_in_a_url_or_a_key(String candidate) {
        assertThatThrownBy(() -> TransactionReference.of(candidate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null() {
        assertThatThrownBy(() -> TransactionReference.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_a_reference_longer_than_the_column() {
        assertThatThrownBy(() -> TransactionReference.of("R".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
        assertThat(TransactionReference.of("R".repeat(128)).value()).hasSize(128);
    }

    /** The caller's key and the ledger's identity are different things, and must not be confused. */
    @Test
    void is_a_value_distinct_from_the_ledger_own_identity() {
        assertThat(TransactionReference.of("PAYMENT-1")).isEqualTo(TransactionReference.of("PAYMENT-1"));
        assertThat(TransactionReference.of("PAYMENT-1")).isNotEqualTo(TransactionReference.of("PAYMENT-2"));
        assertThat(TransactionReference.of("A")).isLessThan(TransactionReference.of("B"));
        assertThat(TransactionReference.of("PAYMENT-1")).hasToString("PAYMENT-1");
    }
}
