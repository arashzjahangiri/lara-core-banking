/**
 * The Quarkus application: configuration, health checks and the wiring that joins the two worlds.
 *
 * <p>Use cases are annotation-free, so something has to construct them. That something is a CDI
 * producer in this package:
 *
 * <pre>{@code
 * @ApplicationScoped
 * class UseCaseConfig {
 *     @Produces
 *     PostTransaction postTransaction(LedgerRepository ledger) {
 *         return new PostTransaction(ledger);
 *     }
 * }
 * }</pre>
 *
 * <p>Quarkus resolves each port to its infrastructure adapter at build time and hands it in, so
 * a missing binding fails the <em>build</em> rather than surfacing at startup. The use case never
 * learns that a framework exists.
 *
 * <p>This module depends on every other, which is why the ArchUnit rules live in its test
 * sources: it is the only place whose classpath can see all four layers at once.
 */
package io.lara.ledger.bootstrap;
