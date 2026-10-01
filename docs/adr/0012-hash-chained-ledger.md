# 12. Hash-chained ledger

**Status:** Accepted
**Date:** 2026-10-01

## Context

The schema refuses `UPDATE` and `DELETE` on postings with a trigger, which stops mutation
through the database. It does not answer a different question: *can anyone prove, later,
that nothing was changed?*

A trigger can be dropped and recreated. A backup can be restored with one row edited. The
data files can be modified with the server stopped. None of those leave a trace, and an
auditor asking whether the ledger is the same ledger as last quarter currently has nothing
to check against.

## Decision

Each transaction stores a SHA-256 hash covering its own content **and the hash of the
transaction before it**. Change any posting and its hash changes, which changes the hash of
the next transaction, and so on to the end of the ledger — so a single edit cannot be
hidden by repairing one row.

The hash covers everything that would change a transaction's meaning: identity, reference,
instant, and every leg in order with its account, side, amount and currency. Fields are
joined with an ASCII unit separator, so an account id ending in digits and an amount
beginning with them cannot run together and let two different transactions collide.

Genesis is sixty-four zeros rather than a random value, so the chain is reproducible from
the data alone and verification does not depend on a stored secret.

`GET /ledger/integrity` walks the chain and reports the **first** break. Everything after
it fails too, since each hash depends on the one before, so listing them all would be
noise. It returns 200 either way: a broken chain is a finding, not a failure of the
request, and a monitor should read the body.

### Serialising the chain

A chain needs a single order, so appends take a pessimistic lock on a one-row
`ledger_chain_head` table holding the last hash and sequence number. Without it, two
concurrent appends both read the same predecessor and the chain forks — verification then
fails on data nobody touched.

This serialises the tail of the write path. It is a genuine throughput ceiling and the
price of having one verifiable sequence.

## What this does and does not prove

**It detects tampering. It does not prevent it.** Anyone able to rewrite rows can also
recompute every hash from the edit forward and leave a chain that verifies perfectly.

What stops that is publishing the head hash somewhere the operator does not control — a
counterparty, a notary, an append-only log on separate infrastructure, a daily hash in a
newspaper if you are feeling traditional. Then an attacker must also forge the published
value, which is a much harder problem.

That publication is out of scope here, and saying so is more honest than implying the
chain alone makes the ledger tamper-proof. What it does buy, on its own, is that casual or
partial tampering — one row changed, a backup restored from the wrong point, a botched
migration — becomes immediately visible instead of silently plausible.

## Alternatives considered

**Trust the append-only trigger alone.** Already in place and genuinely valuable, but it
only guards the path through the running database. It says nothing about backups, offline
files, or a trigger that was dropped for an afternoon.

**Sign each transaction with a private key.** Stronger: an attacker would need the key as
well as database access. Rejected for now because key management is the whole problem —
a key the application can reach is a key an attacker with the application can reach — and
the benefit is small until the hashes are published externally anyway.

**A Merkle tree rather than a linear chain.** Allows proving one transaction's membership
without replaying everything, which matters at scale. Rejected as premature: the linear
chain is simpler to verify and reason about, and the queries that would benefit do not
exist yet.

**Hash per account rather than one global chain.** Removes the write-path lock, which is
the main cost of this decision. Rejected because it gives up a single global order, and
"the ledger as a whole is intact" becomes a weaker statement. Worth revisiting if the lock
ever becomes the bottleneck — it is the obvious next move.

## Consequences

Tampering is detectable, and the verifier names the sequence number, the transaction and
whether the content changed or the chain itself was broken. A test proves this by disabling
the append-only trigger, altering an amount, re-enabling it, and asserting the verifier
reports `CONTENT_ALTERED`.

Every append now takes a row lock, so writes are serialised at the tail.

One subtlety that cost real debugging time and is worth recording: `Instant` carries
nanoseconds while PostgreSQL `timestamptz` stores microseconds, so an untruncated instant
**changes as it round-trips through the database** and the chain broke on data nobody had
touched. `LedgerTransaction` now truncates `occurredAt` to microseconds in its constructor,
so the in-memory value and the stored value are the same thing. Any field added to the hash
in future must be checked for the same hazard.
