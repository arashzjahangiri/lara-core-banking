/**
 * Saga steps and the outbound ports they drive. Annotation-free, as in the other services.
 *
 * <p>The orchestrator's calls to accounts, risk and ledger are ports rather than HTTP clients,
 * which is what lets the whole workflow — including every failure branch and its compensation —
 * be driven in a unit test with in-memory stand-ins and no network.
 */
package io.lara.payments.application;
