package io.lara.payments.fakes;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.lara.payments.application.RemoteServiceException;
import io.lara.payments.application.SchemeAcknowledgement;
import io.lara.payments.application.SchemeGateway;
import io.lara.payments.application.SchemeSubmission;

/**
 * The settlement scheme, under the test''s control.
 *
 * <p>The only step that runs after money has moved, so this is where compensation is triggered
 * from. {@link #reject} is how a chaos test produces the one failure the saga has to undo rather
 * than merely report.
 */
// No @Priority. A @Priority-annotated alternative is enabled application-wide, which would
// replace the real adapter in every test — including ResilienceTest, whose entire subject is
// what happens when the real adapter cannot reach anything. Enabled per-profile instead, by
// StubbedDependenciesProfile#getEnabledAlternatives.
@Alternative
@ApplicationScoped
public class ScriptedScheme implements SchemeGateway {

    private final AtomicReference<SchemeAcknowledgement> answer =
            new AtomicReference<>(SchemeAcknowledgement.accepted("accepted"));
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    public void accept() {
        answer.set(SchemeAcknowledgement.accepted("accepted"));
        failure.set(null);
    }

    public void reject(String why) {
        answer.set(SchemeAcknowledgement.rejected(why));
        failure.set(null);
    }

    /** Unreachable, which must never be read as a rejection — see TransferOrchestrator#settle. */
    public void failWith(String why) {
        failure.set(new RemoteServiceException("scheme", why));
    }

    public void reset() {
        accept();
        calls.set(0);
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public SchemeAcknowledgement submit(SchemeSubmission submission) {
        calls.incrementAndGet();
        RuntimeException broken = failure.get();
        if (broken != null) {
            throw broken;
        }
        return answer.get();
    }
}