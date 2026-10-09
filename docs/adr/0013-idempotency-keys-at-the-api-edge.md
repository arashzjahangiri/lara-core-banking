# 13. Idempotency keys at the API edge

**Status:** Accepted
**Date:** 2026-10-02

## Context

ADR-0007 made the ledger idempotent by a caller-supplied reference: posting twice with the same
reference records one transaction. That guarantee is real and the saga depends on it heavily —
it is what makes a crashed step safe to re-drive.

It is also not enough.

A client sends `POST /payments/transfers` and the connection drops before the response arrives.
The client now knows nothing. The transfer may have been created and completed; it may never have
been recorded at all. Its only options are to retry — and risk paying twice — or not to retry, and
risk not paying at all.

ADR-0007 does not help here, because the duplicate does not reach the ledger as a duplicate. The
second request creates a **second saga**, with a second transfer id and therefore a second posting
reference. Two sagas, two references, two postings, and the ledger deduplicates neither because
from its point of view they are two different payments.

## Decision

`POST /payments/transfers` requires an `Idempotency-Key` header. The service stores the key, a
fingerprint of the request body, and the response it produced. A replay with the same key and the
same body returns the **original response**, byte for byte.

The key is required rather than optional.

## Why the guarantee is "return the original response"

"Do not create a duplicate" is the weaker version and it is not sufficient. If a retry were
answered with a freshly created transfer — or with a 200 and no body — the client would end up
holding two ids for one payment, or none. Both are worse than an error, because the client cannot
tell either has happened.

So a replay returns what the first attempt returned, including its transfer id. From the client's
side the retry is indistinguishable from a slow first response, which is exactly what it should
be.

## Why the header is mandatory

An optional safety mechanism is a trap for whoever did not read the documentation, and the people
who skip the documentation are the ones who most need the protection. Requiring the header costs
a careful client nothing and stops a careless one paying twice.

## How the race is settled

The key is the primary key of its table, and the claim is a single
`insert ... on conflict do nothing`. Of two concurrent first attempts, exactly one insert
succeeds.

A read followed by a write would not do. Between the two statements there is a window wide enough
for both callers to find nothing and both to conclude they were first, and that window is widest
precisely when the system is busiest.

The protocol is therefore claim-then-fill:

1. Insert the key with no response. Winning the insert means owning the key.
2. Do the work.
3. Write the response against the key.

Between steps 1 and 3 the row exists with a null response, and that state is meaningful: a request
with this key is running right now. A duplicate arriving in that window is told so — `409` — rather
than being handed a half-finished answer.

## What happens to a failed attempt

The key is released. Caching a failure would turn one transient error into a permanent one for the
whole retention window: the client would keep being handed the same error no matter how healthy
the system had become. A retry after a failure is entitled to a real attempt.

## Consequences

Two idempotency mechanisms now exist and both are needed. They protect different things:

| | ADR-0007 reference | This ADR's key |
|---|---|---|
| Who supplies it | derived from the transfer id | the client |
| What it protects | the ledger posting | the saga |
| What it prevents | one payment recorded twice | one request becoming two payments |
| Where it is checked | ledger | payments |

A client that reuses a key for a different request gets `409` rather than a silent wrong answer.
That is a deliberate choice to fail loudly: returning the first transfer's response would tell the
caller their second, different payment had succeeded when it was never attempted.

Keys expire, and the expiry is a real deadline rather than a tidy-up. Retention is one day by
default: long enough for any HTTP retry, short enough that the table stays small. A client
retrying after that gets a genuine second attempt, so a key is not a permanent record of
anything.

Without pruning the table would grow for as long as the service runs. The rows are small, which is
exactly why it would go unnoticed until the index stopped fitting in memory and every `POST`
slowed down together. Pruning runs in bounded batches rather than one statement, because a single
unbounded delete after a busy period takes a long lock on a table every incoming request needs —
turning a cleanup job into an outage.
