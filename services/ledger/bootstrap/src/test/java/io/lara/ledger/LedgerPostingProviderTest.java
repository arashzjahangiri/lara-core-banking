package io.lara.ledger;

import java.time.Instant;
import java.util.Map;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;


import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.Money;
import io.lara.ledger.domain.PostingLeg;
import io.lara.ledger.domain.TransactionId;
import io.lara.ledger.domain.TransactionReference;

/**
 * Verifies the ledger still publishes what {@code accounts} says it needs.
 *
 * <p>The two services share no code, which is deliberate — but it means the ledger could rename a
 * field and both would still compile, with the break surfacing in production as balances that
 * quietly stop moving. This closes that gap: the consumer's pact is replayed against the real
 * event this service produces, and the ledger's own build fails if they no longer agree.
 *
 * <p>The message is built from the <strong>production</strong> serialiser rather than a
 * hand-written example. A fixture would verify that someone once wrote matching JSON; this
 * verifies what the service actually emits.
 *
 * <p>The pact directory is relative to this module, not the repository root — Pact resolves
 * {@code @PactFolder} against the module's working directory, and pointing it at "build/pacts"
 * silently finds nothing rather than failing loudly.
 */
@io.quarkus.test.junit.QuarkusTest
@Provider("ledger")
@PactFolder("../../../build/pacts")
@ExtendWith(PactVerificationInvocationContextProvider.class)
class LedgerPostingProviderTest {

    @jakarta.inject.Inject
    io.lara.ledger.infrastructure.persistence.TransactionPostedEvents events;

    @BeforeEach
    void before(PactVerificationContext context) {
        context.setTarget(new MessageTestTarget());
    }

    @TestTemplate
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    /**
     * Produces the event exactly as the outbox would, for one account of a real transaction.
     *
     * <p>Mirrors {@code TransactionPostedEvents}: one event per account, carrying every leg that
     * touched it. If that producer changes shape, this stops matching the consumer's pact.
     */
    @PactVerifyProvider("a transaction posted to an account")
    MessageAndMetadata aTransactionPostedToAnAccount() throws Exception {
        AccountId account = AccountId.of("CUSTOMER000001");

        LedgerTransaction transaction = LedgerTransaction.of(
                TransactionId.newId(),
                TransactionReference.of("PAYMENT-4417"),
                Instant.parse("2026-10-01T12:00:00Z"),
                PostingLeg.credit(account, Money.of(125_000, "EUR")),
                PostingLeg.debit(AccountId.of("BANK.CASH.EUR"), Money.of(125_000, "EUR")));

        // The production serialiser, not a hand-written copy of it. If TransactionPostedEvents
        // changes shape, this verification fails — which is the whole point.
        String payload = events.payloadFor(transaction, account, transaction.legsFor(account));

        return new MessageAndMetadata(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of("contentType", "application/json"));
    }
}
