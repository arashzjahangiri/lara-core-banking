package io.lara.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.lara.accounts.domain.PostingDirection;

/**
 * The failure the contract exists to prevent, demonstrated.
 *
 * <p>`LedgerPostingContractTest` states what this service needs the ledger to publish. This shows
 * what happens when the ledger stops meeting it — a renamed field or a different spelling of
 * `side` stops the consumer rather than being guessed at.
 *
 * <p>Showing the failure mode is what makes the contract's value concrete. A pact nobody can
 * break is a document; one with a demonstrated breach is a test.
 */
class ContractBreachTest {

    @Test
    @DisplayName("a re-spelled side is refused, not guessed at")
    void a_respelled_side_is_refused() {
        assertThat(PostingDirection.parse("CREDIT")).isEqualTo(PostingDirection.CREDIT);
        assertThat(PostingDirection.parse("credit")).as("case is tolerated").isEqualTo(PostingDirection.CREDIT);

        // A value the ledger has never published must stop the consumer. Guessing would apply a
        // movement the wrong way round, which is worse than failing.
        assertThatThrownBy(() -> PostingDirection.parse("INBOUND"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INBOUND");
        assertThatThrownBy(() -> PostingDirection.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a leg that moves nothing is refused")
    void a_zero_leg_is_refused() {
        assertThatThrownBy(() -> new io.lara.accounts.application.LedgerPostingEvent.Leg(
                PostingDirection.CREDIT, 0, "EUR"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
