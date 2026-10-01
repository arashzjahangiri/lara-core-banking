/**
 * The screening use case and the ports it depends on. Annotation-free, as in the other services.
 *
 * <p>Dependencies arrive through the constructor, so the use case can be built with {@code new}
 * in a test. The single wiring class lives in the bootstrap module.
 */
package io.lara.risk.application;