/**
 * Adapters connecting the application's ports to the outside world.
 *
 * <p>This is where Quarkus goes, unrestrained: JPA entities annotated {@code @Entity}, REST
 * resources, Kafka producers and consumers, and CDI beans implementing the ports declared in
 * the application module.
 *
 * <p>Two rules keep the boundary real:
 *
 * <ul>
 *   <li><strong>Persistence types never leave.</strong> A {@code LedgerTransaction} from the
 *       domain is mapped to a {@code LedgerTransactionEntity} here and back again. The
 *       duplication is deliberate and is the price of a framework-free domain.
 *   <li><strong>Validation annotations belong on DTOs, not on domain types.</strong> Request
 *       objects carry {@code @NotNull} and friends; domain records validate themselves.
 * </ul>
 *
 * <p>Panache is deliberately not used. It places persistence methods on the object itself,
 * which is precisely what this structure exists to prevent.
 */
package io.lara.ledger.infrastructure;
