package io.lara.payments.infrastructure.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.payments.application.NotAwaitingApprovalException;
import io.lara.payments.infrastructure.rest.TransferDtos.ProblemResponse;

/**
 * Deciding on a transfer that is not waiting becomes 409.
 *
 * <p>Almost always a stale screen: two checkers open the same queue, one acts, the other clicks a
 * minute later. The message names the state the transfer is actually in, so the second checker
 * can work out what happened without calling anyone.
 */
@Provider
public class NotAwaitingApprovalMapper implements ExceptionMapper<NotAwaitingApprovalException> {

    @Override
    public Response toResponse(NotAwaitingApprovalException wrongState) {
        return Response.status(Response.Status.CONFLICT)
                .entity(new ProblemResponse("not-awaiting-approval", wrongState.getMessage()))
                .build();
    }
}