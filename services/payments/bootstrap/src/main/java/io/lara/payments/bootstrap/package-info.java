/**
 * The Quarkus application: configuration and the single place where wiring happens.
 *
 * <p>Nothing depends on this package. It exists to assemble the other three, which is why the
 * architecture tests live beside it — this is the only module whose classpath sees all four
 * layers at once.
 */
package io.lara.payments.bootstrap;
