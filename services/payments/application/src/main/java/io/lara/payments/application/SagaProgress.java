package io.lara.payments.application;

/**
 * What one saga step did.
 *
 * <p>The caller is a loop that must know whether to come back. Returning a boolean would collapse
 * the two reasons for stopping into one, and they are not the same: a parked transfer is healthy
 * and waiting for a person, while a finished one is done. A driver that could not tell them apart
 * would either abandon the first or keep polling the second forever.
 */
public enum SagaProgress {

    /** A step was taken and there is more to do. Call again. */
    ADVANCED,

    /**
     * Waiting on a human, and nothing automatic will change that.
     *
     * <p>The recovery sweep skips these. A transfer can sit here for days and that is correct
     * behaviour, not a stall.
     */
    PARKED,

    /** The transfer reached a terminal state. There is nothing further to do, ever. */
    FINISHED
}