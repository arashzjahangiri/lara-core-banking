package io.lara.payments.application;

import java.util.List;
import java.util.Optional;

import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferReference;
import io.lara.payments.domain.TransferStatus;

/**
 * Where sagas are kept.
 *
 * <p>Loading and saving are separate calls rather than a single "update" because a saga step
 * reads, decides and writes, and the decision happens in the domain between the two. Hiding that
 * behind one method would push the decision into the adapter, which is where it least belongs.
 */
public interface Transfers {

    Optional<StoredTransfer> find(TransferId id);

    Optional<StoredTransfer> findByReference(TransferReference reference);

    /**
     * Writes a new saga.
     *
     * <p>Separate from {@link #update} because the two fail differently and must. An insert that
     * collides is a duplicate transfer; an update that collides is a concurrency conflict. One
     * method would have to guess which happened.
     */
    void add(Transfer transfer);

    /**
     * Writes a saga that already exists, failing if someone else changed it first.
     *
     * <p>Implementations must raise {@link ConcurrentTransferModificationException} when the
     * stored revision no longer matches the one that was read. Two things touch a saga at once
     * more often than it looks — the orchestrator advancing a step and the recovery sweep
     * deciding the same saga is stuck — and a silent last-write-wins would move a completed
     * transfer back to in-flight.
     *
     * <p>Checking against the version carried by {@link StoredTransfer} rather than re-reading
     * the row is the whole mechanism. A re-read always finds the current revision, so the
     * comparison would always pass and the lock would be decorative.
     */
    void update(StoredTransfer transfer);

    /**
     * Sagas stuck in a non-terminal state, oldest first.
     *
     * <p>Used by the recovery sweep. Terminal sagas are excluded in the query rather than
     * filtered afterwards, because the number of finished transfers only ever grows and the
     * number of in-flight ones does not.
     */
    List<StoredTransfer> stuckIn(List<TransferStatus> states, java.time.Instant notTouchedSince, int limit);

    /**
     * Counts one resumption against a saga and reports the new total.
     *
     * <p>Committed on its own, separately from whatever the resumption then does. That ordering
     * is the point: a saga that fails in a way that rolls back its own transaction would
     * otherwise roll back the record of having been attempted too, and the sweep would retry it
     * forever without the counter ever moving.
     *
     * @return the number of resumptions including this one
     */
    int recordRecoveryAttempt(TransferId id);
}