package io.lara.payments.infrastructure.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.payments.infrastructure.rest.TransferDtos.ProblemResponse;

/**
 * A broken domain rule becomes 422: the request parsed, but cannot be acted on.
 *
 * <p>An invalid IBAN, an unknown currency code, a transfer to the account it came from — all of
 * them land here with the domain's own message, which names the rule rather than the field. The
 * same mapping the other three services use, so one client can treat them alike.
 */
@Provider
public class DomainRuleMapper implements ExceptionMapper<IllegalArgumentException> {

    private static final int UNPROCESSABLE_ENTITY = 422;

    @Override
    public Response toResponse(IllegalArgumentException broken) {
        return Response.status(UNPROCESSABLE_ENTITY)
                .entity(new ProblemResponse("domain-rule-violated", broken.getMessage()))
                .build();
    }
}