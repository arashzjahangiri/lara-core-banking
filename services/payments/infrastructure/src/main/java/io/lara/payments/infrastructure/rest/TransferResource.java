package io.lara.payments.infrastructure.rest;

import java.time.Clock;
import java.util.Currency;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.narayana.jta.QuarkusTransaction;

import io.lara.payments.application.RequestTransfer;
import io.lara.payments.application.StoredTransfer;
import io.lara.payments.application.Transfers;
import io.lara.payments.domain.Iban;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.infrastructure.idempotency.IdempotencyKeys;
import io.lara.payments.infrastructure.rest.TransferDtos.RequestTransferBody;
import io.lara.payments.infrastructure.rest.TransferDtos.TransferResponse;
import io.lara.payments.infrastructure.saga.TransferSagaRunner;

/**
 * The payments service's HTTP surface.
 *
 * <h2>Idempotency</h2>
 *
 * <p>{@code Idempotency-Key} is required, not optional. A client whose connection drops mid-POST
 * cannot tell whether a transfer was created, so without a key its only options are to retry and
 * risk paying twice, or not to retry and risk not paying at all. Making the header optional would
 * leave that trap open for anyone who did not read the documentation.
 *
 * <p>The guarantee is stronger than "do not duplicate": a replay returns the <em>original</em>
 * response, including the original transfer id. Answering a retry with a freshly created transfer
 * would leave the client holding two ids for one payment, which is worse than an error.
 *
 * <p>This is a different mechanism from ADR-0007's posting reference and both are needed.
 * ADR-0007 stops the ledger recording a payment twice; this stops a second saga ever starting.
 *
 * <h2>Driving the saga inline</h2>
 *
 * <p>The request drives the saga to completion rather than handing it to a worker. That makes the
 * call slower and ties it to three other services being up, which a high-volume system would not
 * accept — but it makes the API answerable in one round trip, and the recovery sweep is the
 * backstop for anything left unfinished. A production system at scale should reconsider it.
 */
@Path("/payments")
@Produces(MediaType.APPLICATION_JSON)
public class TransferResource {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final int MAX_KEY_LENGTH = 255;

    private final RequestTransfer requestTransfer;
    private final TransferSagaRunner saga;
    private final Transfers transfers;
    private final IdempotencyKeys keys;
    private final ObjectMapper json;
    private final Clock clock;

    public TransferResource(
            RequestTransfer requestTransfer,
            TransferSagaRunner saga,
            Transfers transfers,
            IdempotencyKeys keys,
            ObjectMapper json,
            Clock clock) {

        this.requestTransfer = requestTransfer;
        this.saga = saga;
        this.transfers = transfers;
        this.keys = keys;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Requests a transfer and drives it as far as it will go.
     *
     * <p>Not {@code @Transactional}. The saga commits each step separately and the idempotency
     * claim has to be visible to concurrent callers before the work starts, so a transaction
     * around the whole method would defeat both.
     */
    @POST
    @Path("/transfers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response request(
            @HeaderParam(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid RequestTransferBody body) {

        String key = requireKey(idempotencyKey);
        String fingerprint = IdempotencyKeys.fingerprintOf(serialise(body));

        Optional<IdempotencyKeys.StoredResponse> existing =
                keys.claim(key, fingerprint, clock.instant());

        if (existing.isPresent()) {
            return replay(key, fingerprint, existing.get());
        }

        // The key is ours. From here every exit must either store a response or release it,
        // or a retry would be told forever that a request is still running.
        try {
            Transfer transfer = QuarkusTransaction.requiringNew().call(() -> requestTransfer.request(
                    Iban.of(body.debtorIban()),
                    Iban.of(body.creditorIban()),
                    Money.of(body.amountMinorUnits(), Currency.getInstance(body.currency())),
                    body.requestedBy()));

            saga.drive(transfer.id());

            TransferResponse response = TransferResponse.of(load(transfer.id()));
            keys.complete(key, Response.Status.CREATED.getStatusCode(), serialise(response));

            return Response.status(Response.Status.CREATED)
                    .location(java.net.URI.create("/payments/transfers/" + transfer.id()))
                    .entity(response)
                    .build();

        } catch (RuntimeException failed) {
            // A failed attempt must not be cached. The client's retry is entitled to a real
            // attempt, not a replay of an error that may long since have stopped happening.
            keys.release(key);
            throw failed;
        }
    }

    @GET
    @Path("/transfers/{id}")
    public TransferResponse byId(@PathParam("id") String id) {
        return TransferResponse.of(load(TransferId.of(id)));
    }

    /**
     * Answers a duplicate request.
     *
     * <p>Three cases, and they are genuinely different. A different body under the same key is a
     * client bug and gets 409 — returning the first transfer's response would tell the caller
     * their second, different payment had succeeded. A request still running also gets 409,
     * because there is no answer yet and inventing one would be worse than saying so. Only a
     * completed, matching request gets the stored response.
     */
    private Response replay(String key, String fingerprint, IdempotencyKeys.StoredResponse stored) {
        if (!stored.matches(fingerprint)) {
            throw new IdempotencyConflictException(
                    "idempotency key " + key + " was already used for a different request");
        }
        if (stored.isStillRunning()) {
            throw new IdempotencyConflictException(
                    "a request with idempotency key " + key + " is still in progress");
        }

        return Response.status(stored.status().orElseThrow())
                .type(MediaType.APPLICATION_JSON)
                .entity(stored.body().orElseThrow())
                .build();
    }

    private Transfer load(TransferId id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> transfers.find(id))
                .map(StoredTransfer::transfer)
                .orElseThrow(() -> new NotFoundException("no transfer with id " + id));
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException(IDEMPOTENCY_HEADER + " is required");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BadRequestException(
                    IDEMPOTENCY_HEADER + " must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    private String serialise(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            // Records of strings, longs and dates. If this fails, returning a half-formed
            // response would be worse than failing the request.
            throw new IllegalStateException("could not serialise " + value.getClass(), impossible);
        }
    }
}
