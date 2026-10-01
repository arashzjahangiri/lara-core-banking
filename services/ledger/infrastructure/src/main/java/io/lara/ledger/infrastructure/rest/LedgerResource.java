package io.lara.ledger.infrastructure.rest;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.lara.ledger.application.FindTransaction;
import io.lara.ledger.application.GetAccountBalance;
import io.lara.ledger.application.PostTransaction;
import io.lara.ledger.application.PostTransactionCommand;
import io.lara.ledger.application.PostTransactionResult;
import io.lara.ledger.application.ReverseTransaction;
import io.lara.ledger.application.VerifyChain;
import io.lara.ledger.domain.AccountBalance;
import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.EntrySide;
import io.lara.ledger.domain.Money;
import io.lara.ledger.domain.PostingLeg;
import io.lara.ledger.domain.TransactionId;
import io.lara.ledger.domain.TransactionReference;
import io.lara.ledger.infrastructure.rest.LedgerDtos.BalanceResponse;
import io.lara.ledger.infrastructure.rest.LedgerDtos.IntegrityResponse;
import io.lara.ledger.infrastructure.rest.LedgerDtos.PostTransactionRequest;
import io.lara.ledger.infrastructure.rest.LedgerDtos.PostingLegRequest;
import io.lara.ledger.infrastructure.rest.LedgerDtos.TransactionResponse;

/**
 * The ledger's HTTP surface.
 *
 * <p>A thin adapter: it translates wire shapes into domain types, calls a use case, and translates
 * the result back. No business rule is decided here, which is why the same behaviour is covered by
 * fast unit tests against the use cases rather than only through HTTP.
 */
@Path("/ledger")
@Produces(MediaType.APPLICATION_JSON)
public class LedgerResource {

    private final PostTransaction postTransaction;
    private final FindTransaction findTransaction;
    private final GetAccountBalance getAccountBalance;
    private final ReverseTransaction reverseTransaction;
    private final VerifyChain verifyChain;

    public LedgerResource(PostTransaction postTransaction,
            FindTransaction findTransaction,
            GetAccountBalance getAccountBalance,
            ReverseTransaction reverseTransaction,
            VerifyChain verifyChain) {

        this.postTransaction = postTransaction;
        this.findTransaction = findTransaction;
        this.getAccountBalance = getAccountBalance;
        this.reverseTransaction = reverseTransaction;
        this.verifyChain = verifyChain;
    }

    /**
     * Records a transaction.
     *
     * <p>201 when this call recorded it, <strong>200 when the reference had already been
     * recorded</strong>. Both are successes: a retry got exactly what it asked for, and the money
     * moved once. Returning an error for a retry would push callers towards not retrying, which is
     * the opposite of what a payment system needs.
     */
    @POST
    @Path("/transactions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response post(@Valid PostTransactionRequest request) {
        PostTransactionResult result = postTransaction.post(new PostTransactionCommand(
                TransactionReference.of(request.reference()),
                toLegs(request.legs())));

        return Response
                .status(result.created() ? Response.Status.CREATED : Response.Status.OK)
                .location(URI.create("/ledger/transactions/" + result.transaction().id()))
                .entity(TransactionResponse.of(result.transaction()))
                .build();
    }

    /**
     * Reverses a transaction by posting its opposite.
     *
     * <p>201 when this call recorded the reversal, 409 when one already exists — reversing twice
     * would restore the original movement, which is a new posting rather than an undo, and should
     * be asked for deliberately if it is wanted.
     */
    @POST
    @Path("/transactions/{id}/reversal")
    public Response reverse(@PathParam("id") String id) {
        PostTransactionResult result = reverseTransaction.reverse(TransactionId.of(id));

        return Response
                .status(result.created() ? Response.Status.CREATED : Response.Status.OK)
                .location(URI.create("/ledger/transactions/" + result.transaction().id()))
                .entity(TransactionResponse.of(result.transaction()))
                .build();
    }

    @GET
    @Path("/transactions/{id}")
    public Response byId(@PathParam("id") String id) {
        return findTransaction.byId(TransactionId.of(id))
                .map(TransactionResponse::of)
                .map(body -> Response.ok(body).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    /** Lets a caller that lost a response discover whether its posting landed, without re-sending. */
    @GET
    @Path("/transactions")
    public Response byReference(@QueryParam("reference") String reference) {
        if (reference == null || reference.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new LedgerDtos.ProblemResponse("missing-parameter", "reference is required"))
                    .build();
        }
        return findTransaction.byReference(TransactionReference.of(reference))
                .map(TransactionResponse::of)
                .map(body -> Response.ok(body).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * The balance of an account, optionally as it stood at a past instant.
     *
     * @param asOf an ISO-8601 instant; defaults to now
     */
    @GET
    @Path("/accounts/{id}/balance")
    public BalanceResponse balance(@PathParam("id") String id, @QueryParam("asOf") String asOf) {
        AccountId account = AccountId.of(id);
        AccountBalance balance = asOf == null || asOf.isBlank()
                ? getAccountBalance.current(account)
                : getAccountBalance.asAt(account, Instant.parse(asOf));
        return BalanceResponse.of(balance);
    }

    /**
     * Whether the hash chain still adds up.
     *
     * <p>Returns 200 either way — a broken chain is a finding, not a failure of this request, and
     * a monitor should read the body rather than the status. Detection only: anyone able to
     * rewrite rows can recompute the chain forward, which ADR-0012 says plainly.
     */
    @GET
    @Path("/integrity")
    public IntegrityResponse integrity() {
        return IntegrityResponse.of(verifyChain.verify());
    }

    private static List<PostingLeg> toLegs(List<PostingLegRequest> legs) {
        return legs.stream()
                .map(leg -> new PostingLeg(
                        AccountId.of(leg.account()),
                        EntrySide.valueOf(leg.side()),
                        Money.of(leg.amountMinorUnits(), leg.currency())))
                .toList();
    }
}
