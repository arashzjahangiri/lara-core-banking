package io.lara.payments.infrastructure.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.payments.domain.FourEyesViolationException;
import io.lara.payments.infrastructure.rest.TransferDtos.ProblemResponse;

/**
 * Self-approval becomes 403, not 422.
 *
 * <p>The distinction is real and worth keeping. 422 says the request could not be acted on; 403
 * says it was understood perfectly and the caller is not allowed to make it. A checker who sees
 * 422 goes looking for a malformed field, which is the wrong place entirely.
 */
@Provider
public class FourEyesMapper implements ExceptionMapper<FourEyesViolationException> {

    @Override
    public Response toResponse(FourEyesViolationException violation) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new ProblemResponse("four-eyes-violation", violation.getMessage()))
                .build();
    }
}