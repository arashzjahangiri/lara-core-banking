package io.lara.risk.infrastructure.rest;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.risk.infrastructure.rest.ScreeningDtos.ProblemResponse;

/**
 * Gives a 404 the same typed body as every other failure here.
 *
 * <p>Without this, an unknown reference returns the container's default HTML error page while
 * every other error returns JSON. A client then needs two parsers, and the one it reaches for
 * first is the wrong one.
 */
@Provider
public class NotFoundMapper implements ExceptionMapper<NotFoundException> {

    @Override
    public Response toResponse(NotFoundException missing) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(new ProblemResponse("unknown-screening", missing.getMessage()))
                .build();
    }
}