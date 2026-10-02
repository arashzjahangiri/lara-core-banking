package io.lara.risk.infrastructure.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.risk.infrastructure.rest.ScreeningDtos.ProblemResponse;

/**
 * A broken domain rule becomes 422: the request is well formed but cannot be acted on.
 *
 * <p>A blank beneficiary name, an unknown currency code, limits set in a currency the amount is
 * not in — all of them land here, and the domain's own message is what the caller needs. The
 * same mapping the other two services use, so one client can treat all three alike.
 *
 * <p>422 rather than 400 is the distinction worth keeping: 400 says the request could not be
 * parsed, and these parsed perfectly well.
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