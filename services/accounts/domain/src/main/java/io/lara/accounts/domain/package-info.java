/**
 * Customers, accounts and IBANs.
 *
 * <p>No dependencies. This package knows nothing of Quarkus, Jakarta, persistence or JSON, which
 * is what lets its tests run in milliseconds with no container.
 *
 * <p>It also holds no balances. The ledger owns money; this service owns identity.
 */
package io.lara.accounts.domain;
