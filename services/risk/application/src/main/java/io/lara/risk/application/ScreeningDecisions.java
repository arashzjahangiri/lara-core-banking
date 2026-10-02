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
     * Writes a decision down and returns the one that is actually stored.
     *
     * <p>The return value is not ceremony. Two requests carrying the same reference can both find
     * nothing on the lookup above and both arrive here, and exactly one of them can win. The
     * loser must be told what the winner decided rather than its own answer, because its answer
     * was never recorded and quoting it would hand a caller a decision that does not exist.
     *
     * <p>Implementations must never overwrite an existing row. A decision that has been returned
     * to a caller is history, and history does not get edited.
     *
     * @return the stored decision: the one just written, or the one that was already there
     */
    RecordedScreening record(RecordedScreening screening);
}