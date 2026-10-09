package io.lara.payments.infrastructure.client;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** The accounts service's HTTP surface, as a typed client. */
@Path("/accounts-api")
@RegisterRestClient(configKey = "accounts-api")
@Produces(MediaType.APPLICATION_JSON)
public interface AccountsClient {

    @GET
    @Path("/accounts/{iban}")
    AccountResponse byIban(@PathParam("iban") String iban);

    /**
     * Mirrors the accounts service's response rather than importing it.
     *
     * <p>Only the fields payments actually uses are declared. Jackson ignores the rest, so the
     * accounts service can add a field without breaking this — which is the useful half of not
     * sharing a jar.
     */
    record AccountResponse(
            String iban,
            String customerId,
            String ledgerAccountId,
            String currency,
            String status) {
    }
}