package io.lara.payments.infrastructure.client;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;

/**
 * Reports each dependency's circuit breaker.
 *
 * <h2>Why an open breaker does not make this pod unready</h2>
 *
 * <p>This check always answers UP, and that is deliberate rather than lazy.
 *
 * <p>Readiness controls whether traffic is routed here. An open breaker says a <em>dependency</em>
 * is down, not that this instance is unhealthy — and every other instance is talking to the same
 * risk service, so they would all go unready together. Kubernetes would then pull the entire
 * deployment out of rotation, turning one dependency's outage into a total outage of payments,
 * including the endpoints that still work: reading a transfer, approving one, declining one.
 *
 * <p>Worse, an unready pod stops receiving requests but keeps running, so nothing drives the
 * recovery sweep's traffic and the queue of stalled sagas grows silently behind a service that
 * looks merely absent rather than broken.
 *
 * <p>So the state is published as <strong>data</strong>. A dashboard or an alert rule can read
 * {@code risk=OPEN} and page someone, which is the correct response to a dependency being down.
 * Taking the pod out of rotation is not.
 */
@Readiness
@ApplicationScoped
public class OutboundDependenciesHealthCheck implements HealthCheck {

    /** The names given to the breakers on the three adapters. */
    private static final List<String> DEPENDENCIES = List.of("accounts", "risk", "ledger");

    private final CircuitBreakerMaintenance breakers;

    public OutboundDependenciesHealthCheck(CircuitBreakerMaintenance breakers) {
        this.breakers = breakers;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder response = HealthCheckResponse.named("outbound dependencies");

        for (String dependency : DEPENDENCIES) {
            response.withData(dependency, stateOf(dependency));
        }

        // Always UP. See the class comment: a dependency being down is not this pod being
        // unhealthy, and reporting it as such would take the whole deployment out of rotation.
        return response.up().build();
    }

    /**
     * Reads one breaker's state.
     *
     * <p>A breaker that has never been exercised has no state to report, and
     * {@link CircuitBreakerMaintenance} throws rather than inventing one. That is not a failure —
     * it means no call has gone through it yet — so it is reported as such rather than being
     * allowed to break the health endpoint.
     */
    private String stateOf(String dependency) {
        try {
            CircuitBreakerState state = breakers.currentState(dependency);
            return state.name();
        } catch (RuntimeException notYetUsed) {
            return "UNUSED";
        }
    }
}
