package io.lara.accounts.infrastructure.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.accounts.infrastructure.rest.AccountDtos.ProblemResponse;

/**
 * A broken domain rule becomes 422: the request is well formed but cannot be acted on. An invalid
 * IBAN and a closed account being reopened both land here, and the domain's own message —
 * "IBAN check digits do not match" — is what the caller needs.
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
