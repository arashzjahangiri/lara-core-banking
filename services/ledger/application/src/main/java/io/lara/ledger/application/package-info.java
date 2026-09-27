/**
 * Use cases and the ports they depend on.
 *
 * <p>A use case orchestrates domain objects to satisfy one request, and declares what it needs
 * from the outside world as a plain interface — a <em>port</em>. Adapters implementing those
 * ports live in the infrastructure module and are supplied at runtime.
 *
 * <p>This package is <strong>annotation-free</strong>. No {@code @ApplicationScoped}, no
 * {@code @Inject}, no {@code @Transactional}. Dependencies arrive through the constructor, so
 * any use case can be built with {@code new} in a test with no container and no mocking
 * framework. Configuration values arrive the same way: a use case that reads config directly is
 * tied to a runtime it should not know about.
 *
 * <p>The cost is one wiring class in the bootstrap module. That is the only place where this
 * code and the framework meet.
 */
package io.lara.ledger.application;
