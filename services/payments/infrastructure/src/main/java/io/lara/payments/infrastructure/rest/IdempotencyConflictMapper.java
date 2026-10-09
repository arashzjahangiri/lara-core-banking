package io.lara.payments.infrastructure.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.payments.infrastructure.rest.TransferDtos.ProblemResponse;

/** Turns an unanswerable idempotency key into a 409 with the usual problem body. */
@Provider
public class IdempotencyConflictMapper implements ExceptionMapper<IdempotencyConflictException> {

    @Override
    public Response toResponse(IdempotencyConflictException conflict) {
        return Response.status(Response.Status.CONFLICT)
                .entity(new ProblemResponse("idempotency-key-conflict", conflict.getMessage()))
                .build();
    }
}