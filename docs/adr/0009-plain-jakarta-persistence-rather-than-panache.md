# 9. Plain Jakarta Persistence rather than Panache

**Status:** Accepted
**Date:** 2026-10-01

## Context

Quarkus ships Panache, an active-record layer that removes most of the ceremony from
Jakarta Persistence. An entity extends `PanacheEntity` and gains `persist()`, `delete()`,
`listAll()` and a concise query syntax. It is genuinely good, and for most Quarkus
services it is the right default.

It also puts persistence methods on the object itself.

## Decision

Plain Jakarta Persistence, with entities confined to `infrastructure` and an
`EntityManager` used directly by the adapters.

Panache is not used anywhere.

## Alternatives considered

**Panache active record** (`entity.persist()`). Rejected. `ADR-0008` keeps the domain free
of any framework, and an active-record entity is the opposite arrangement: the object
knows how to save itself, so the model and the database are the same thing. The domain
would either have to extend `PanacheEntity` — ending the separation entirely — or the
entities would stay in `infrastructure` anyway, at which point Panache's main convenience
is no longer being used.

**Panache repositories** (`PanacheRepository<T>`), which keep the entity clean and put the
methods on a repository instead. This is a much closer call, and in a project without the
module split it would probably win. Rejected because the adapters here implement ports the
application already defines — `LedgerTransactions` has exactly four methods, each shaped by
what a use case needs — and inheriting a repository's generic API would add a second,
wider surface alongside it. One of the two would be the real contract and the other
decoration.

**MapStruct for the entity-to-domain mapping.** Rejected after trying to justify it. Every
field needs a custom conversion: `AccountId` to `String`, `Money` to a `BIGINT` plus a
currency code, a list of legs to an ordered child collection. MapStruct generates useful
code when fields line up by name and type, and here none do, so it would contribute an
annotation processor and a build step while the conversions stayed hand-written anyway.
The mapping lives on the entities as `from()` and `toDomain()`, next to the thing being
mapped.

## Consequences

More code. `JpaLedgerTransactions` writes its own JPQL, and the entities carry explicit
mapping methods that Panache or MapStruct would have shortened.

In exchange the port stays narrow and honest: there is no `delete`, no `update` and no
generic `findAll`, because the ledger has no use for them and the interface should not
offer what must never be called. With an inherited repository API, those methods would
exist whether or not anyone intended them to.

The mapping code is also where the append-only rule is visible — nothing in the adapter
can express a mutation, so the physical trigger in the schema is a second guard rather
than the only one.

This decision is worth revisiting per service. A future service doing conventional CRUD
over a few tables would be better served by Panache, and choosing differently there would
be a sign of judgement rather than inconsistency.
