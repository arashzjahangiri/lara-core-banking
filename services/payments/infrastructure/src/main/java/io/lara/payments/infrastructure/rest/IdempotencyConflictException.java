package io.lara.payments.infrastructure.rest;

/**
 * An idempotency key was used in a way that cannot be answered.
 *
 * <p>Either the same key arrived with a different body, which is a client bug, or a request under
 * that key is still running, in which case there is no answer yet. Both are 409, and both are
 * better reported than papered over: returning the first transfer's response to a different
 * request would tell a caller their second payment had succeeded when it had never been
 * attempted.
 */
public class IdempotencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException(String message) {
        super(message);
    }
}