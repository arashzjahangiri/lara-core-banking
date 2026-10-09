package io.lara.payments.application;

/**
 * The risk service, as this service needs it.
 *
 * <p>Synchronous, per ADR-0002: screening is a command and the saga cannot take the next step
 * without an answer.
 *
 * <p>Implementations must throw {@link RemoteServiceException} when they cannot get one. There is
 * no fallback verdict and there must never be: a synthesised {@code ALLOW} would let an
 * unscreened payment through, and a synthesised {@code BLOCK} would reject valid transfers for
 * the length of an outage. Not knowing is its own outcome and the saga handles it.
 */
public interface RiskScreening {

    ScreeningVerdict screen(ScreeningCommand command);
}