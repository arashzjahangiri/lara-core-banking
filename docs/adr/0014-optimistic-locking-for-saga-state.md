# 14. Optimistic locking for saga state

**Status:** Accepted
**Date:** 2026-10-13

## Context

Two things touch a saga at once more often than it looks. The orchestrator advances a step while
the recovery sweep decides the same saga has stalled; two instances of the service both pick up
the same transfer; a checker approves one at the moment a sweep resumes it.

Without any guard the slower write silently overwrites the faster one. A completed transfer goes
back to in flight, or a compensation is forgotten, and nothing fails — which is the worst kind of
bug, because the system goes on looking healthy.

## Decision

A `version` column on `transfer`, checked on every write. A write built from a stale read is
refused with `ConcurrentTransferModificationException`, and the caller reloads and retries,
bounded.

## Why optimistic rather than pessimistic

Saga steps are short and genuine contention is rare. The common case by far is the orchestrator
working alone on a transfer nobody else has looked at, and a pessimistic row lock makes every one
of those pay for a collision that almost never happens.

A lock also has to be held across the step, and a saga step makes a network call to another
service. Holding a database row lock across an HTTP request is how one slow dependency becomes a
pile of blocked transactions and then a connection pool exhaustion — the dependency's outage
turns into the database's outage.

So the collision is detected rather than prevented. When one does happen it is an exception to
retry against fresh state, not a queue to wait in.

## The mistake that made this ADR necessary

The first implementation was wrong in a way that would have passed a careless test.

`update` loaded the row, copied the new state onto it, and let Hibernate's version check do the
rest. But the row was re-read *inside* `update`, so the generated `update ... where version = ?`
quoted whatever the current revision was — which always matched. The lock was decorative and every
write won.

The fix is `StoredTransfer`, which carries the revision the saga was **read** at. A step reads,
goes away to decide — possibly calling another service, which takes time — and only then writes
back. The comparison has to be against the state the decision was made on, and nothing re-read at
write time can tell you that.

Hibernate's own check is still there and still earns its place: it covers the narrow window
between the explicit comparison and the flush, where a third party commits in between.

The general lesson is worth keeping. **A lock that is checked against freshly read state is not a
lock.** The version has to travel with the data from the read that the decision was based on.

## Why the version is not on the aggregate

`Transfer` knows nothing about it. A revision number is a fact about a stored row, not about a
payment, and the domain has no business knowing it is stored at all.

The cost is that the orchestrator threads `StoredTransfer` around rather than plain `Transfer`.
That is visible and slightly noisy, which is the correct trade: the alternative hides a
persistence concept inside a domain object where nobody would think to look for it.

## Consequences

Every write path has to be prepared to lose. `TransferSagaRunner` retries a conflicted step
against freshly loaded state, bounded — a saga that keeps losing is a saga something is wrong
with, and spinning on it forever turns a conflict into an outage.

The recovery sweep needs no special handling to avoid racing the live orchestrator. It goes
through the same path, so it takes the same lock, and when it loses it simply reloads. That is the
right outcome: the saga did move, just not by that hand.

`recordRecoveryAttempt` deliberately does **not** touch the version. It is bookkeeping about the
saga rather than a change to it, and bumping the version there would make the live orchestrator
lose a race it should have won.

Retrying a conflicted step is only safe because every downstream call is idempotent. That is not a
coincidence — ADR-0007 and ADR-0013 are what make this one workable, and removing either would
make a retry here dangerous.
