package io.lara.risk.infrastructure.rest;

import java.util.Currency;

import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.lara.risk.application.ScreenTransfer;
import io.lara.risk.application.ScreeningDecisions;
import io.lara.risk.domain.CustomerId;
import io.lara.risk.domain.Money;
import io.lara.risk.domain.PartyName;
import io.lara.risk.domain.RecordedScreening;
import io.lara.risk.domain.ScreeningReference;
import io.lara.risk.domain.ScreeningRequest;
import io.lara.risk.infrastructure.rest.ScreeningDtos.ScreeningRequestBody;
import io.lara.risk.infrastructure.rest.ScreeningDtos.ScreeningResponse;

/**
 * The risk service's HTTP surface.
 *
 * <p>Synchronous, and ADR-0002 is the reason. Commands are blocking calls and facts are events;
 * screening is a command, because payments cannot take the next step until it has an answer.
 * Doing it as an event round trip would mean the saga had to park itself and be woken up, which
 * is a great deal of machinery to avoid a call that takes a few milliseconds.
 *
 * <p>A thin adapter, as everywhere else: wire shapes in, domain types out, no rule decided here.
 *
 * <h2>Why 200 rather than 201</h2>
 *
 * <p>A screening that has already been done returns the original decision, so this endpoint is
 * not reliably creating anything. Returning 201 on the first call and 200 on a retry would make
 * the status code depend on whether a previous attempt happened to get through — which is exactly
 * the thing a retrying client cannot know.
 */
@Path("/risk")
@Produces(MediaType.APPLICATION_JSON)
public class RiskResource {

    private final ScreenTransfer screenTransfer;
    private final ScreeningDecisions decisions;

    public RiskResource(ScreenTransfer screenTransfer, ScreeningDecisions decisions) {
        this.screenTransfer = screenTransfer;
        this.decisions = decisions;
    }

    /**
     * Screens a transfer, or returns the answer this reference already has.
     *
     * <p>Transactional at this boundary rather than inside the use case. The use case is
     * annotation-free by design — the architecture test enforces it — and the read-then-insert
     * that resolves a concurrent duplicate has to sit inside one transaction to mean anything.
     */
    @POST
    @Path("/screenings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public ScreeningResponse screen(@Valid ScreeningRequestBody body) {
        ScreeningRequest request = new ScreeningRequest(
                ScreeningReference.of(body.reference()),
                CustomerId.of(body.customerId()),
                Money.of(body.amountMinorUnits(), Currency.getInstance(body.currency())),
                PartyName.of(body.beneficiaryName()));

        return ScreeningResponse.of(screenTransfer.screen(request));
    }

    /**
     * Looks up a decision that was already made.
     *
     * <p>Here so a decision can be re-read without the side effect of making one. A support agent
     * asking "what did risk say about this" should not be able to cause a screening by asking.
     */
    @GET
    @Path("/screenings/{reference}")
    public ScreeningResponse byReference(@PathParam("reference") String reference) {
        RecordedScreening screening = decisions
                .findByReference(ScreeningReference.of(reference))
                .orElseThrow(() -> new NotFoundException("no screening with reference " + reference));

        return ScreeningResponse.of(screening);
    }
}
