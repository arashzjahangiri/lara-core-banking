package io.lara.ledger.application;

import java.util.Objects;

/**
 * Walks the ledger's hash chain and reports the first place it stops adding up.
 *
 * <p>Deliberately a port rather than a use case with logic in it. Verification reads every
 * transaction in sequence, and doing that through the normal repository would either load the
 * whole ledger into memory or make a query per row. The adapter streams it.
 */
public interface VerifyChain {

    /**
     * Recomputes every transaction's hash from its content and its predecessor, and compares.
     *
     * <p>Stops at the first break. Everything after it would fail too, since each hash depends on
     * the one before, so reporting them all would be noise.
     */
    ChainIntegrity verify();

    /** A verifier that is wired but disabled, for environments where the chain is not maintained. */
    static VerifyChain disabled() {
        return () -> ChainIntegrity.intact(0);
    }

    /** Guards against a null being passed where a verifier is required. */
    static VerifyChain requireNonNull(VerifyChain verifier) {
        return Objects.requireNonNull(verifier, "verifier must not be null");
    }
}
