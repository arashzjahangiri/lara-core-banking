package io.lara.ledger.infrastructure.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.ledger.application.PostingRejectedException;
import io.lara.ledger.infrastructure.rest.LedgerDtos.ProblemResponse;

/**
 * Turns a refusal into a status code.
 *
 * <p>The switch is exhaustive over a sealed hierarchy and has <strong>no default</strong>. Adding a
 * new reason for refusing a posting therefore stops this file compiling until someone decides what
 * it means over HTTP — which is the point. A {@code default -> 400} would let a new case silently
 * inherit a status that is probably wrong.
 */
@Provider
public class PostingRejectedMapper implements ExceptionMapper<PostingRejectedException> {

    /** Not in the Jakarta REST enum, so named here rather than left as a bare 422. */
    private static final int UNPROCESSABLE_ENTITY = 422;

    @Override
    public Response toResponse(PostingRejectedException rejection) {
        Status status = switch (rejection) {
            // The account does not exist. Not a 404: the request itself is addressed correctly,
            // it is the content that names something unknown.
            case PostingRejectedException.UnknownAccount e ->
                new Status(UNPROCESSABLE_ENTITY, "unknown-account");

            case PostingRejectedException.AccountCurrencyMismatch e ->
                new Status(UNPROCESSABLE_ENTITY, "account-currency-mismatch");

            case PostingRejectedException.PostingLimitExceeded e ->
                new Status(UNPROCESSABLE_ENTITY, "posting-limit-exceeded");

            // The reference is already taken by a different movement. A genuine conflict with
            // existing state, and the one case a caller must not simply retry.
            case PostingRejectedException.ReferenceReused e ->
                new Status(Response.Status.CONFLICT.getStatusCode(), "reference-reused");
        };

        return Response.status(status.code())
                .entity(new ProblemResponse(status.error(), rejection.getMessage()))
                .build();
    }

    private record Status(int code, String error) {
    }
}
