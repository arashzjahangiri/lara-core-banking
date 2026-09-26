# 4. Transactional outbox for event publishing

**Status:** Accepted
**Date:** 2026-09-26

## Context

When `ledger` records a posting it must do two things: commit the rows to PostgreSQL, and
publish a `TransactionPosted` event to Kafka so that `accounts` can update its balance
read model and `payments` can complete the saga.

These are two different systems, so they cannot share a transaction. That leaves the
dual-write problem, and it has no safe ordering:

- **Database first, then publish.** A crash between the two loses the event permanently.
  The money moved and nothing downstream will ever know. Balances silently drift.
- **Publish first, then write.** If the write fails, an event has been published claiming
  a posting that does not exist. Consumers act on a lie.

Retrying does not fix either case, because the failure is precisely the inability to know
whether the first step happened.

## Decision

`ledger` does not publish to Kafka at all.

Within the same database transaction that writes the postings, it inserts a row into an
`outbox` table describing the event. Because it is one transaction, either both the
postings and the outbox row commit, or neither does. The dual-write problem disappears by
construction.

**Debezium** then reads the PostgreSQL write-ahead log, picks up committed outbox rows and
publishes them to Kafka. It sees only what actually committed, and its replication slot
position means it resumes exactly where it left off after a restart.

Every service that publishes events uses this pattern. No service writes to Kafka directly
from application code.

## Alternatives considered

**Publish inside the transaction and hope.** This is the dual-write bug described above.
It is the most common implementation and it is wrong; it usually survives testing because
the failure window is small, and then loses events under production load.

**Polling the outbox table with a scheduled job.** Correct, and much simpler to operate
than change data capture — no replication slot, no connector to run. Rejected as the
primary approach because it adds polling latency and load, and because demonstrating CDC
is a deliberate goal of this project. It remains the right choice for a team that does not
want to operate Debezium, and is worth noting as such in the README.

**Two-phase commit between PostgreSQL and Kafka.** Kafka does not participate in XA, and
distributed transactions across a database and a broker are a well-known operational
hazard. Not viable.

**Event sourcing, with the event log as the source of truth.** A legitimate design for a
ledger and arguably the purest fit. Deferred rather than rejected: it changes the
persistence model of the whole service, and the append-only postings table already gives
the audit properties that matter most. Revisit in the open-ended phase.

## Consequences

Publishing is now reliable, and the guarantee is **at least once** rather than exactly
once. Debezium can republish a row after a restart, so every consumer must be idempotent
and deduplicate on event id. That is not a workaround; it is the correct contract for
message delivery, and the consumer tests assert it explicitly by delivering the same event
twice.

Ordering is preserved per partition. Events are keyed by account so that all movements
affecting one account are ordered relative to each other, which is what the balance read
model requires.

The cost is operational: Debezium is another component to run, and PostgreSQL must be
configured with logical replication (`wal_level=logical`). Docker Compose carries this
configuration so a clean clone still works.

Outbox rows are pruned after publication, on a schedule. The ledger postings themselves
are never deleted — the outbox is a transport buffer, not an audit record.
