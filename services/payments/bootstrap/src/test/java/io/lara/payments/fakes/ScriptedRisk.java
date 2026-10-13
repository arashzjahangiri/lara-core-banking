package io.lara.payments.fakes;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.lara.payments.application.RemoteServiceException;
import io.lara.payments.application.RiskScreening;
import io.lara.payments.application.ScreeningCommand;
import io.lara.payments.application.ScreeningVerdict;

/**
 * Risk, under the test''s control.
 *
 * <p>Allows everything by default, so a test that is about something else does not have to say
 * so. {@link #failWith} makes it unreachable, which is how the chaos tests inject an outage at
 * this step.
 */
// No @Priority. A @Priority-annotated alternative is enabled application-wide, which would
// replace the real adapter in every test — including ResilienceTest, whose entire subject is
// what happens when the real adapter cannot reach anything. Enabled per-profile instead, by
// StubbedDependenciesProfile#getEnabledAlternatives.
@Alternative
@ApplicationScoped
public class ScriptedRisk implements RiskScreening {

    private final AtomicReference<ScreeningVerdict> answer =
            new AtomicReference<>(ScreeningVerdict.allow("no sanctions match"));
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    public void answerWith(ScreeningVerdict verdict) {
        answer.set(verdict);
        failure.set(null);
    }

    /** Makes the dependency unreachable. Not a verdict — the saga must not read it as one. */
    public void failWith(String why) {
        failure.set(new RemoteServiceException("risk", why));
    }

    public void recover() {
        failure.set(null);
    }

    public void reset() {
        answer.set(ScreeningVerdict.allow("no sanctions match"));
        failure.set(null);
        calls.set(0);
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public ScreeningVerdict screen(ScreeningCommand command) {
        calls.incrementAndGet();
        RuntimeException broken = failure.get();
        if (broken != null) {
            throw broken;
        }
        return answer.get();
    }
}