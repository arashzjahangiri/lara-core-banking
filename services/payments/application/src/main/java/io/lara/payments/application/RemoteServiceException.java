package io.lara.payments.application;

import java.util.Objects;

/**
 * A dependency could not be reached, or answered in a way that cannot be interpreted.
 *
 * <p>Distinct from a negative answer, and that distinction is the point. A risk service that says
 * "blocked" has decided something; a risk service that times out has decided nothing. Treating
 * the second as the first would reject valid transfers during an outage, and treating it as an
 * allow would let unscreened payments through — so it is neither, and the saga stops instead.
 *
 * <p>Carries which dependency failed, because the orchestrator's log line is the first thing
 * anyone reads when transfers start stalling.
 */
public final class RemoteServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String service;

    public RemoteServiceException(String service, String message, Throwable cause) {
        super(service + ": " + message, cause);
        this.service = Objects.requireNonNull(service);
    }

    public RemoteServiceException(String service, String message) {
        this(service, message, null);
    }

    public String service() {
        return service;
    }
}