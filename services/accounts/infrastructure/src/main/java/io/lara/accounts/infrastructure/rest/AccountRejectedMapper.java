package io.lara.accounts.infrastructure.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.accounts.application.AccountRejectedException;
import io.lara.accounts.infrastructure.rest.AccountDtos.ProblemResponse;

/**
 * Maps a refusal to a status code, exhaustively over the sealed hierarchy and with no default —
 * so a new reason stops this file compiling until someone decides what it means over HTTP.
 */
@Provider
public class AccountRejectedMapper implements ExceptionMapper<AccountRejectedException> {

    private static final int UNPROCESSABLE_ENTITY = 422;

    @Override
    public Response toResponse(AccountRejectedException rejection) {
        Status status = switch (rejection) {
            // The path names something that does not exist, so 404 rather than 422.
            case AccountRejectedException.UnknownCustomer e ->
                new Status(Response.Status.NOT_FOUND.getStatusCode(), "unknown-customer");
            case AccountRejectedException.UnknownAccount e ->
                new Status(Response.Status.NOT_FOUND.getStatusCode(), "unknown-account");

            // The request is addressed correctly; its content is not actionable yet.
            case AccountRejectedException.CustomerNotVerified e ->
                new Status(UNPROCESSABLE_ENTITY, "customer-not-verified");

            // A genuine conflict with existing state.
            case AccountRejectedException.LedgerAccountTaken e ->
                new Status(Response.Status.CONFLICT.getStatusCode(), "ledger-account-taken");
        };

        return Response.status(status.code())
                .entity(new ProblemResponse(status.error(), rejection.getMessage()))
                .build();
    }

    private record Status(int code, String error) {
    }
}
