package io.lara.risk.application;

import java.util.Optional;

import io.lara.risk.domain.RecordedScreening;
import io.lara.risk.domain.ScreeningReference;

/**
 * Where decisions are written down and looked back up.
 *
 * <p>The lookup is what makes asking twice safe. Payments retries a screening that timed out
 * rather than guessing, and without this the retry would be re-evaluated against whatever the
 * limits and the running total look like a few seconds later — so a transfer could be allowed on
 * the second attempt having been blocked on the first, or the reverse.
 */
public interface ScreeningDecisions {

    Optional<RecordedScreening> findByReference(ScreeningReference reference);

    /**
     * Writes a decision down.
     *
     * <p>Implementations must treat a duplicate reference as a conflict rather than an overwrite.
     * A decision that has been returned to a caller is history, and history does not get edited.
     */
    void record(RecordedScreening screening);
}