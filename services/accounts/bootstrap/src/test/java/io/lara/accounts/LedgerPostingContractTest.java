package io.lara.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.messaging.Message;
import au.com.dius.pact.core.model.messaging.MessagePact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The contract this service needs the ledger to keep.
 *
 * <p>`accounts` parses `ledger.account` events by hand rather than importing the ledger's event
 * class, which keeps the services decoupled — and leaves a gap: the ledger could rename a field
 * and both services would still compile, with the break only appearing in production as balances
 * that quietly stop moving.
 *
 * <p>This is a <strong>consumer-driven</strong> contract. It states exactly what this service
 * reads: field names, types, and the fact that `legs` is an array that may hold more than one
 * entry. The pact it writes is verified against the ledger in that service's own build, so a
 * breaking change there fails there, before it is merged.
 *
 * <p>Note what the contract does <em>not</em> pin: fields the ledger publishes that this service
 * ignores. A consumer-driven contract constrains only what is actually consumed, which is what
 * lets the producer keep evolving.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "ledger", pactVersion = PactSpecVersion.V3, providerType = ProviderType.ASYNCH)
class LedgerPostingContractTest {

    private static final String DESCRIPTION = "a transaction posted to an account";

    @Pact(consumer = "accounts", provider = "ledger")
    MessagePact postingEvent(MessagePactBuilder builder) {
        return builder
                .expectsToReceive(DESCRIPTION)
                .withMetadata(Map.of("contentType", "application/json"))
                .withContent(new au.com.dius.pact.consumer.dsl.PactDslJsonBody()
                        .uuid("transactionId")
                        .stringType("reference", "PAYMENT-4417")
                                                // Not a fixed-width datetime pattern. The ledger serialises with
                        // Instant.toString(), which omits trailing zeros — "12:00:00Z" carries no
                        // fractional part at all while "12:00:00.123456Z" carries six digits. A
                        // fixed pattern looks tidier and is simply wrong; this states what the
                        // consumer actually accepts, which is what Instant.parse can read.
                        .stringMatcher("occurredAt",
                                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z",
                                "2026-10-01T12:00:00Z")
                        .stringType("account", "CUSTOMER000001")
                        .stringMatcher("currency", "[A-Z]{3}", "EUR")
                        .minArrayLike("legs", 1)
                            .stringMatcher("side", "DEBIT|CREDIT", "CREDIT")
                            .integerType("amountMinorUnits", 125000L)
                            .stringMatcher("currency", "[A-Z]{3}", "EUR")
                        .closeArray())
                .toPact();
    }

    /**
     * Proves the consumer can actually read what the contract describes.
     *
     * <p>A pact that nobody parses is a document, not a test. Running the real parsing path over
     * the example body is what makes the contract binding on this side as well as the producer's.
     */
    @Test
    @PactTestFor(pactMethod = "postingEvent")
    @DisplayName("the consumer reads the event it says it expects")
    void the_consumer_reads_what_the_contract_describes(List<Message> messages) throws Exception {
        assertThat(messages).hasSize(1);
        String payload = new String(messages.get(0).contentsAsBytes());

        var parsed = new ObjectMapper().readTree(payload);

        assertThat(parsed.get("transactionId").asText()).isNotBlank();
        assertThat(parsed.get("account").asText()).isNotBlank();
        assertThat(parsed.get("currency").asText()).hasSize(3);
        assertThat(parsed.get("legs")).isNotEmpty();

        var leg = parsed.get("legs").get(0);
        assertThat(io.lara.accounts.domain.PostingDirection.parse(leg.get("side").asText())).isNotNull();
        assertThat(leg.get("amountMinorUnits").asLong()).isPositive();
    }

}
