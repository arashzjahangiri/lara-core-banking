package io.lara.risk.application;

import io.lara.risk.domain.SanctionsList;

/**
 * Where the sanctions list comes from.
 *
 * <p>Read on every screening rather than cached at startup. A regulator can publish an addition
 * at any hour, and a list loaded once when the pod booted is a list that is stale for as long as
 * the pod lives — which, for a service that rarely restarts, can be weeks.
 */
public interface SanctionsSource {

    SanctionsList current();
}