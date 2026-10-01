/**
 * Adapters implementing the application's ports: decision persistence and the screening REST API.
 *
 * <p>Everything framework-shaped lives at or below this package, and nothing above it refers
 * back down. The build enforces that before ArchUnit gets a chance to.
 */
package io.lara.risk.infrastructure;