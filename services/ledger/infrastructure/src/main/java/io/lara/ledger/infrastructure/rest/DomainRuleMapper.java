package io.lara.ledger.infrastructure.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import io.lara.ledger.infrastructure.rest.LedgerDtos.ProblemResponse;

/**
 * Turns a broken domain rule into 422.
 *
 * <p>A request whose legs do not balance is syntactically perfect — every field is present and
 * well-formed, so Bean Validation passes — and still impossible to act on. That is exactly what
 * 422 is for, and why these are distinct from the 400 a malformed body gets.
 *
 * <p>The message is the domain's own, which is written to be read: <em>"transaction does not
 * balance: debits EUR 100.00 but credits EUR 99.50"</em> tells a caller what to fix.
 */
@Provider
public class DomainRuleMapper implements ExceptionMapper<IllegalArgumentException> {

    /** Not in the Jakarta REST enum, so named here rather than left as a bare 422. */
    private static final int UNPROCESSABLE_ENTITY = 422;

    @Override
    public Response toResponse(IllegalArgumentException broken) {
        return Response.status(UNPROCESSABLE_ENTITY)
                .entity(new ProblemResponse("domain-rule-violated", broken.getMessage()))
                .build();
    }
}
