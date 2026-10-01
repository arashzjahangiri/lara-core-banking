/**
 * Adapters implementing the application's ports: saga persistence, the outbound clients, REST.
 *
 * <p>Everything framework-shaped lives at or below this package. Nothing here is referenced from
 * the application or domain modules, and the build enforces that before ArchUnit gets a chance to.
 */
package io.lara.payments.infrastructure;
