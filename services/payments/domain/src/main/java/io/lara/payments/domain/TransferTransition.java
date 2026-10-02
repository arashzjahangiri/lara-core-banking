package io.lara.payments.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One move the transfer made, kept so the whole path can be read back.
 *
 * <p>ADR-0003 chose orchestration partly so that "where is my transfer" could be answered with a
 * single request. The current state answers that; this answers the harder follow-up, which is how
 * it got there. A transfer that ended {@code COMPENSATED} is only intelligible with the steps
 * that preceded it.
 *
 * <p>The note is free text on purpose. It explains a particular move — which limit was hit, which
 * dependency timed out — and that is detail for a human reading the history, not something code
 * should ever branch on. Code branches on {@link RejectionReason}.
 */
public record TransferTransition(TransferStatus from, TransferStatus to, Instant at, String note) {

    public TransferTransition {
        Objects.requireNonNull(from, "a transition must have a source state");
        Objects.requireNonNull(to, "a transition must have a target state");
        Objects.requireNonNull(at, "a transition must be timestamped");
        Objects.requireNonNull(note, "a transition must have a note, even an empty one");
    }

    @Override
    public String toString() {
        return from + " -> " + to + " at " + at + (note.isEmpty() ? "" : " (" + note + ")");
    }
}