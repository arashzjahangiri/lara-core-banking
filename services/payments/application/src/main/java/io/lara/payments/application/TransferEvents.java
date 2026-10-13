package io.lara.payments.application;

import io.lara.payments.domain.Transfer;

/**
 * Where a finished transfer is announced.
 *
 * <p>ADR-0002 draws the line: commands are synchronous, facts are asynchronous. A completed
 * transfer is a fact, and other services — and eventually the UI — should learn about it without
 * polling.
 *
 * <p>Implementations must write the event in the <em>same transaction</em> as the state change
 * that produced it. That is the whole point of a transactional outbox: an event written
 * separately can exist without the fact it describes, or a fact can happen with nobody ever told.
 * ADR-0004 settled this for the ledger and the reasoning is unchanged here.
 */
public interface TransferEvents {

    /**
     * Records the fact that this transfer reached a terminal state.
     *
     * <p>Called for {@code COMPLETED}, {@code REJECTED} and {@code COMPENSATED}.
     *
     * <p>Deliberately <strong>not</strong> for {@code FAILED}. That state means the system does
     * not know what happened — the saga ran out of attempts, or a reversal could not be posted —
     * and there is no fact to publish. Announcing "TransferFailed" would invite consumers to act
     * on it, and the correct response to a saga nobody can finish is an operator, not a
     * downstream state change.
     */
    void recordTerminal(Transfer transfer);
}