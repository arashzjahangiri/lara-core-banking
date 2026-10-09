package io.lara.payments.infrastructure.rest;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.payments.infrastructure.rest.TransferDtos.ProblemResponse;

/** Gives a 404 the same typed body as every other failure here, rather than an HTML page. */
@Provider
public class NotFoundMapper implements ExceptionMapper<NotFoundException> {

    @Override
    public Response toResponse(NotFoundException missing) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(new ProblemResponse("unknown-transfer", missing.getMessage()))
                .build();
    }
}