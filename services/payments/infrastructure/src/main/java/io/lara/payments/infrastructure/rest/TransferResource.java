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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.narayana.jta.QuarkusTransaction;

import io.lara.payments.application.DecideApproval;
import io.lara.payments.application.RequestTransfer;
import io.lara.payments.application.StoredTransfer;
import io.lara.payments.application.TransferQuery;
import io.lara.payments.application.Transfers;
import io.lara.payments.domain.Iban;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferStatus;
import io.lara.payments.infrastructure.idempotency.IdempotencyKeys;
import io.lara.payments.infrastructure.rest.TransferDtos.ApprovalDecisionBody;
import io.lara.payments.infrastructure.rest.TransferDtos.RequestTransferBody;
import io.lara.payments.infrastructure.rest.TransferDtos.TransferPageResponse;
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
    private final DecideApproval decideApproval;
    private final TransferSagaRunner saga;
    private final Transfers transfers;
    private final IdempotencyKeys keys;
    private final ObjectMapper json;
    private final Clock clock;

    public TransferResource(
            RequestTransfer requestTransfer,
            DecideApproval decideApproval,
            TransferSagaRunner saga,
            Transfers transfers,
            IdempotencyKeys keys,
            ObjectMapper json,
            Clock clock) {

        this.requestTransfer = requestTransfer;
        this.decideApproval = decideApproval;
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
     * Transfers matching a filter, newest first.
     *
     * <p>Every parameter is optional, and an absent one means "do not filter on this" rather than
     * "match nothing". That is the behaviour a caller assembling a query string expects, and the
     * opposite reading turns a forgotten parameter into an empty page nobody can explain.
     *
     * <p>Filtered by the debtor''s account rather than by customer. Payments does not own customer
     * identity — accounts does — and inventing a customer filter would mean keeping a second copy
     * of somebody else''s truth in this database. A caller who wants everything one customer sent
     * asks accounts for their IBANs first.
     */
    @GET
    @Path("/transfers")
    public TransferPageResponse list(
            @QueryParam("debtorIban") String debtorIban,
            @QueryParam("status") String status,
            @QueryParam("requestedBy") String requestedBy,
            @QueryParam("page") @jakarta.ws.rs.DefaultValue("0") int page,
            @QueryParam("size") @jakarta.ws.rs.DefaultValue("25") int size) {

        TransferQuery query = TransferQuery.firstPage().onPage(page, size);

        if (debtorIban != null && !debtorIban.isBlank()) {
            query = query.withDebtor(Iban.of(debtorIban));
        }
        if (status != null && !status.isBlank()) {
            query = query.withStatus(parseStatus(status));
        }
        if (requestedBy != null && !requestedBy.isBlank()) {
            query = query.withRequestedBy(requestedBy);
        }

        TransferQuery built = query;
        return TransferPageResponse.of(
                QuarkusTransaction.requiringNew().call(() -> transfers.search(built)));
    }

    /**
     * Parses a status, or says which ones exist.
     *
     * <p>{@code TransferStatus.valueOf} throws an {@code IllegalArgumentException} whose message
     * is the enum class name and the bad value, which tells a caller nothing about what they
     * should have sent. Listing the legal values turns one support question into none.
     */
    private static TransferStatus parseStatus(String status) {
        try {
            return TransferStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown status ''" + status + "''; expected one of "
                    + java.util.Arrays.toString(TransferStatus.values()));
        }
    }

    /**
     * A second person approves a parked transfer.
     *
     * <p>The approval commits on its own, and only then is the saga driven. A checker clicking
     * approve should not wait on three downstream services, and their decision has to survive
     * even if the posting that follows fails — which it can, because the transfer is now in
     * {@code POSTING} and the recovery sweep owns anything left unfinished.
     *
     * <p>Who the checker is arrives in the body for now. Phase 4 brings Keycloak, and this
     * becomes the authenticated principal — at which point the rule stops being advisory, since
     * a caller can currently name anyone. The domain rule is already real; only the identity is
     * a placeholder.
     */
    @POST
    @Path("/transfers/{id}/approval")
    @Consumes(MediaType.APPLICATION_JSON)
    public TransferResponse approve(
            @PathParam("id") String id, @Valid ApprovalDecisionBody body) {

        TransferId transferId = TransferId.of(id);

        QuarkusTransaction.requiringNew().run(() -> decideApproval.approve(transferId, body.actor()));
        saga.drive(transferId);

        return TransferResponse.of(load(transferId));
    }

    /** A second person declines it. Ends in {@code REJECTED} with no posting ever made. */
    @POST
    @Path("/transfers/{id}/decline")
    @Consumes(MediaType.APPLICATION_JSON)
    public TransferResponse decline(
            @PathParam("id") String id, @Valid ApprovalDecisionBody body) {

        TransferId transferId = TransferId.of(id);
        String reason = body.reason() == null || body.reason().isBlank()
                ? "no reason given"
                : body.reason();

        QuarkusTransaction.requiringNew().run(
                () -> decideApproval.decline(transferId, body.actor(), reason));

        return TransferResponse.of(load(transferId));
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
