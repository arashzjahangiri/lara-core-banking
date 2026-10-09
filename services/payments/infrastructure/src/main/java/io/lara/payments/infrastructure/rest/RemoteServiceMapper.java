package io.lara.payments.infrastructure.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.payments.application.RemoteServiceException;
import io.lara.payments.infrastructure.rest.TransferDtos.ProblemResponse;

/**
 * A dependency we could not reach becomes 503, not 500.
 *
 * <p>The distinction is for the client rather than for us: 503 says the request was fine and is
 * worth retrying, while 500 says something is broken here. A saga stalled on an unreachable risk
 * service is the former, and the transfer is still recorded and will be picked up by the recovery
 * sweep — so the caller has lost nothing by trying again.
 */
@Provider
public class RemoteServiceMapper implements ExceptionMapper<RemoteServiceException> {

    @Override
    public Response toResponse(RemoteServiceException unreachable) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(new ProblemResponse(
                        "dependency-unavailable",
                        "could not reach " + unreachable.service() + "; the transfer is recorded "
                                + "and will be retried"))
                .build();
    }
}