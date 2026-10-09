package io.lara.payments.application;

import io.lara.payments.domain.LedgerTransactionRef;

/**
 * The ledger, as this service needs it.
 *
 * <p>Both methods are idempotent by the reference they carry, which is what makes the saga
 * recoverable at all. A step re-driven after a crash posts the same reference again and the
 * ledger collapses it to the transaction it already recorded — so the orchestrator never has to
 * work out whether its previous attempt got through before deciding what to do next.
 *
 * <p>That guarantee is ADR-0007's, and it is the reason {@code TransferReference} is derived from
 * the transfer id rather than generated per attempt.
 */
public interface LedgerPosting {

    /**
     * Moves the money, or returns the transaction that already moved it.
     *
     * @throws LedgerRefusedException  the entry is invalid or the funds are not there; no money moved
     * @throws RemoteServiceException  the ledger could not be reached; the outcome is unknown
     */
    LedgerTransactionRef post(PostingCommand command);

    /**
     * Moves it back, by a contra entry rather than by editing anything.
     *
     * <p>ADR-0011 settled the mechanics. Identified by the transaction being reversed rather than
     * by the transfer's reference, because the saga already holds that id — a {@code POSTED}
     * transfer cannot exist without one — and looking it up again would be a round trip to
     * rediscover something we were told.
     *
     * <p>Idempotent at the ledger: reversing a transaction that has already been reversed returns
     * the existing contra entry rather than posting a second one. That is what makes a recovery
     * sweep safe to run over a saga that was already compensating.
     */
    LedgerTransactionRef reverse(LedgerTransactionRef original, String reason);
}