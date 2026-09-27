/**
 * The ledger domain model: money, accounts, postings and the rules that govern them.
 *
 * <p>This package has <strong>no dependencies</strong>. Not Quarkus, not Jakarta, not a JSON
 * library, not a persistence API. Types here are plain Java over {@code java.*} and nothing
 * more, which is what allows the whole domain suite to run in milliseconds with no container.
 *
 * <p>Objects validate themselves in their constructors rather than carrying validation
 * annotations. A transaction whose legs do not sum to zero cannot be constructed at all.
 *
 * <p>Adding a dependency to this module should require an architecture decision record.
 */
package io.lara.ledger.domain;
