package io.lara.payments.infrastructure.client;

import java.time.Instant;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** The risk service's HTTP surface, as a typed client. */
@Path("/risk")
@RegisterRestClient(configKey = "risk-api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface RiskClient {

    @POST
    @Path("/screenings")
    ScreeningResponse screen(ScreeningRequestBody body);

    record ScreeningRequestBody(
            String reference,
            String customerId,
            long amountMinorUnits,
            String currency,
            String beneficiaryName) {
    }

    record ScreeningResponse(
            String reference,
            String outcome,
            String reason,
            Instant screenedAt) {
    }
}