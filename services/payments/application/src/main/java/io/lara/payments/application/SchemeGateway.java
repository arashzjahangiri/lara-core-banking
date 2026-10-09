package io.lara.payments.application;

/**
 * The settlement scheme, as this service needs it.
 *
 * <p>The step that makes compensation reachable. Everything before the ledger posting can simply
 * be rejected, because no money has moved; this call happens after it has, so a rejection here
 * is the one failure the saga has to undo rather than merely report.
 *
 * <p>Only SEPA transfers go through a scheme. An internal book transfer is finished the moment
 * the ledger accepts it — both accounts are at this bank and nobody outside is involved — so the
 * orchestrator never calls this for one.
 *
 * <p>Implementations must throw {@link RemoteServiceException} when they cannot get an answer. An
 * unreachable scheme is not a rejection: compensating on a timeout would reverse payments the
 * scheme had in fact accepted, which is a far more expensive mistake than waiting.
 */
public interface SchemeGateway {

    SchemeAcknowledgement submit(SchemeSubmission submission);
}