# 16. No fallback may fabricate a success

**Status:** Accepted
**Date:** 2026-10-13

## Context

The payments service makes three synchronous calls per transfer: accounts, risk and ledger. Each
is a chance for a dependency to be slow, broken or absent, so each needs a resilience policy.

Every resilience library offers a fallback — a value to return when the call fails. It is the
most convenient thing in the toolkit, and it is the one this system must not use for these calls.

## Decision

Timeouts, retry with jitter, and a circuit breaker per dependency. **No fallback anywhere.** A
call that cannot be completed raises `RemoteServiceException` and the saga stops where it is.

## Why a fallback is the wrong tool here

A fallback answers a question nobody asked. The caller wanted to know whether this payment may
proceed, and a fallback replies with a value that was never computed from anything.

In this domain both possible fabrications are harmful, in different directions:

- A synthetic **`ALLOW`** lets an unscreened payment through. That is the single worst failure
  this system can have, and it is the easy mistake to make, because it keeps transfers flowing and
  makes the tests go green.
- A synthetic **`BLOCK`** rejects every valid transfer for the length of an outage, and each one
  is recorded as a decision that a person then has to explain to a customer.

Not knowing is its own outcome, distinct from both. The saga is built to sit with it: the transfer
stays in a state it can be resumed from, the recovery sweep owns it, and the caller gets a `503`
saying the request was fine and is worth retrying.

## The shape the mistake takes

It rarely looks like fabrication. It looks like a reasonable default:

```java
// Looks harmless. Reads downstream as "no such account" and rejects a valid transfer.
@Fallback(fallbackMethod = "unknownAccount")
Optional<AccountSummary> find(Iban iban) { ... }
```

`Optional.empty()` is a perfectly ordinary thing for a lookup to return, and the orchestrator
reads it as *the account does not exist* — so during an outage a transfer is rejected as invalid
with a reason the customer would find baffling. The adapter throws instead, and there is a test
pinning exactly that.

The same applies to a `LedgerTransactionRef` invented when the posting could not be confirmed. The
saga would mark the transfer complete over a posting that does not exist.

## Why retry is safe here, and what makes it so

Retry is only safe because every one of these calls is idempotent, and that is a property of the
design rather than a happy accident:

| Call | What makes repeating it safe |
| --- | --- |
| `ledger.post` | reference derived from the transfer id — ADR-0007 |
| `risk.screen` | replays its stored decision for that reference |
| `accounts.find` | a read |
| `ledger.reverse` | the ledger returns the contra entry that already exists |

`abortOn` is the other half and matters more than the retry. A `LedgerRefusedException` is a
decision — the entry is invalid, or the funds are not there — and retrying it four times produces
the same refusal four times while the customer waits.

Jitter is not decoration. Without it every payment that failed during a blip retries at the same
instant, and the dependency coming back up is met by the whole backlog at once, so it falls over
again.

## Why the reversal has no circuit breaker

A breaker stops pointless work against a dependency that is clearly down. That reasoning does not
hold when money has already left a customer's account and is sitting in suspense: the cost of one
more attempt is nothing against the cost of not making it.

Sharing the posting breaker would be worse. A burst of failed postings would open it, and the
reversals that burst created would then be refused without ever reaching the ledger — the system
would stop putting money back precisely when it had most to put back.

## Why an open breaker does not fail readiness

The breaker states are published on `/health/ready` as **data**, and the check always reports
`UP`.

Readiness controls whether traffic is routed to this instance. An open breaker says a *dependency*
is down, not that this instance is unhealthy — and every instance is talking to the same risk
service, so they would all go unready together. Kubernetes would then pull the entire deployment
out of rotation, turning one dependency's outage into a total outage of payments, including the
endpoints that still work: reading a transfer, approving one, declining one.

An unready pod also keeps running while receiving nothing, so the queue of stalled sagas grows
behind a service that looks merely absent rather than broken.

Paging someone on `risk=OPEN` is the correct response to a dependency being down. Taking the pod
out of rotation is not.

## Consequences

Callers must distinguish "could not ask" from "was told no", and the type system makes them:
`RemoteServiceException` and `LedgerRefusedException` are separate types and the orchestrator
branches on both.

A dependency being down means transfers stall rather than fail. That is a deliberate trade — a
stalled transfer is recoverable and a wrongly decided one is not.

Timeouts and budgets are configuration rather than constants, so a budget can be changed for one
environment without a release. The annotations carry defaults, not policy.
