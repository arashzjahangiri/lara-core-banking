package io.lara.payments.infrastructure.client;

import java.time.Instant;
import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The ledger's HTTP surface, as a typed client.
 *
 * <p>Declared separately from the adapter that uses it so the wire shapes stay in one place and
 * the adapter stays about translation. The records below mirror the ledger's own DTOs rather than
 * importing them — the two services share no code, which is the point, and a shared jar would
 * couple their release cycles.
 *
 * <p>That duplication is not free: a field renamed in the ledger compiles fine here and fails at
 * runtime. The Pact contract added in Phase 2 is what catches that, and the same approach is
 * worth extending to these calls.
 */
@Path("/ledger")
@RegisterRestClient(configKey = "ledger-api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface LedgerClient {

    @POST
    @Path("/transactions")
    TransactionResponse post(PostTransactionRequest request);

    /**
     * Reverses a transaction by posting a contra entry.
     *
     * <p>Idempotent at the ledger: a second call returns the reversal that already exists rather
     * than posting another one, which is what makes a repeated compensation harmless.
     */
    @POST
    @Path("/transactions/{id}/reversal")
    TransactionResponse reverse(@PathParam("id") String id);

    /** One side of an entry. The ledger requires every transaction's legs to sum to zero. */
    record PostingLegRequest(String account, String side, Long amountMinorUnits, String currency) {
    }

    record PostTransactionRequest(String reference, List<PostingLegRequest> legs) {
    }

    record TransactionResponse(
            String id,
            String reference,
            Instant occurredAt,
            String currency,
            long totalMinorUnits,
            List<PostingLegResponse> legs) {
    }

    record PostingLegResponse(String account, String side, long amountMinorUnits, String currency) {
    }
}
