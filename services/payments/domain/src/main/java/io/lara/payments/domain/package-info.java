/**
 * The transfer aggregate and the state machine that governs it.
 *
 * <p>No dependencies. ADR-0003 chose orchestration over choreography so that the workflow would
 * be written down in one place; this package is that place, and keeping it framework-free is what
 * lets the whole state machine be exercised in milliseconds with no container.
 *
 * <p>It holds workflow state, never money. Balances belong to the ledger.
 */
package io.lara.payments.domain;
