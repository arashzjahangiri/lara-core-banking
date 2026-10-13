package io.lara.payments.infrastructure.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.smallrye.faulttolerance.api.CircuitBreakerName;

import io.lara.payments.application.RemoteServiceException;
import io.lara.payments.application.RiskScreening;
import io.lara.payments.application.ScreeningCommand;
import io.lara.payments.application.ScreeningVerdict;

/**
 * Asks the risk service, and refuses to invent an answer when it cannot.
 *
 * <p>There is no fallback verdict, deliberately and permanently. A synthesised {@code ALLOW}
 * would let an unscreened payment through — the single worst failure this system can have — and
 * a synthesised {@code BLOCK} would reject every valid transfer for the length of an outage.
 * Not knowing is its own outcome, and the saga is built to sit with it.
 *
 * <p>An unrecognised outcome is also an error rather than a guess. If risk grows a fourth
 * verdict, this fails loudly on the first call instead of quietly mapping it to something
 * convenient.
 *
 * <h2>Retry and the breaker</h2>
 *
 * <p>Retrying is only safe because screening is idempotent by its reference: risk returns the
 * decision it already made rather than evaluating again, so a second attempt cannot produce a
 * different answer than the first. Adding retry to a call without that guarantee is how one
 * screening becomes three entries in a customer''s daily total.
 *
 * <p>Jitter matters more than it looks. Without it, every payment that failed during a blip
 * retries at the same instant, and the dependency coming back up is met by the entire backlog at
 * once — so it falls over again.
 *
 * <p>The breaker then stops the retries once the dependency is clearly down. Four seconds of
 * backoff per transfer across a thousand transfers is a thousand threads waiting on something
 * that is not going to answer; failing immediately is kinder to both services.
 */
@ApplicationScoped
public class RestRiskScreening implements RiskScreening {

    private static final String SERVICE = "risk";

    private final RiskClient risk;

    public RestRiskScreening(@RestClient RiskClient risk) {
        this.risk = risk;
    }

    /**
     * Timeouts live on the REST client, retry and the breaker live here, and every value is
     * overridable through MicroProfile config — the annotation holds the default, not the policy.
     */
    @Override
    @Retry(maxRetries = 3,
            delay = 200, jitter = 150,
            retryOn = RemoteServiceException.class)
    @CircuitBreaker(requestVolumeThreshold = 8, failureRatio = 0.5, delay = 10_000,
            failOn = RemoteServiceException.class)
    @CircuitBreakerName("risk")
    public ScreeningVerdict screen(ScreeningCommand command) {
        RiskClient.ScreeningResponse response;
        try {
            response = risk.screen(new RiskClient.ScreeningRequestBody(
                    command.reference().value(),
                    command.customerId(),
                    command.amount().minorUnits(),
                    command.amount().currency().getCurrencyCode(),
                    command.beneficiaryName()));

        } catch (WebApplicationException answered) {
            throw new RemoteServiceException(SERVICE,
                    "screening " + command.reference() + " failed with "
                            + answered.getResponse().getStatus(), answered);

        } catch (ProcessingException unreachable) {
            throw new RemoteServiceException(SERVICE,
                    "could not screen " + command.reference(), unreachable);
        }

        return toVerdict(response, command);
    }

    private static ScreeningVerdict toVerdict(
            RiskClient.ScreeningResponse response, ScreeningCommand command) {

        return switch (response.outcome()) {
            case "ALLOW" -> ScreeningVerdict.allow(response.reason());
            case "REVIEW" -> ScreeningVerdict.review(response.reason());
            case "BLOCK" -> ScreeningVerdict.block(response.reason());
            default -> throw new RemoteServiceException(SERVICE,
                    "screening " + command.reference() + " returned an outcome this service does "
                            + "not understand: " + response.outcome());
        };
    }
}