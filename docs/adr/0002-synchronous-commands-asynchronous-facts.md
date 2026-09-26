# 2. Synchronous commands, asynchronous facts

**Status:** Accepted
**Date:** 2026-09-26

## Context

Four services have to coordinate to move money. Two communication styles are available —
request/response over HTTP, and events over Kafka — and the usual failure is to pick one
and apply it everywhere.

Making everything synchronous produces a distributed monolith: every service must be up
for any of them to work, and a slow `risk` service makes the whole platform slow. Making
everything asynchronous is worse for this domain, because a transfer genuinely cannot
proceed until `risk` has answered, and pretending otherwise means either blocking on an
event round-trip or moving money that should have been declined.

## Decision

**Ask synchronously, tell asynchronously.**

A service makes a synchronous REST call when it needs an answer before it can continue.
It publishes an event when it has a fact that others may care about but is not waiting on.

For a transfer that gives:

| Hop | Style | Why |
| --- | --- | --- |
| `payments` → `accounts` | REST | Cannot post to an account that does not exist or is closed |
| `payments` → `risk` | REST | Must not move money that was declined; the answer gates the next step |
| `payments` → `ledger` | REST | The money movement itself; the caller needs the outcome |
| `ledger` → everyone | Kafka | The posting is a fact. Nothing is waiting on the balance read model |
| `accounts` balance view | Kafka consumer | Eventually consistent by design |

Synchronous coupling is accepted only where correctness requires it. Everywhere else,
services learn about the world by consuming events.

### Protocol for the synchronous calls

REST over HTTP with JSON, documented by OpenAPI.

### Service-to-service authentication

Internal is not a security boundary. Keycloak issues the tokens, and `payments`
propagates the caller's token onward via `quarkus-oidc-client` rather than using a
service account, so the ledger's audit trail records *who* initiated a movement rather
than which service relayed it.

### Resilience, with one hard exclusion

SmallRye Fault Tolerance provides `@Retry`, `@Timeout` and `@CircuitBreaker`, and all
three are used on synchronous calls.

`@Fallback` is **forbidden on any write path.** A fallback that returns a synthetic
success makes the caller believe a posting succeeded when it did not, and every
downstream check is then reasoning from a fabricated result. A failed posting must
surface as a failure so the saga can compensate. Where a call must be verified, it is
verified by the echoed transaction identity, never by the mere fact that the call
returned.

## Alternatives considered

**Everything asynchronous, including the risk decision.** Rejected. It turns a
request-scoped decision into a correlation problem, and the user is waiting anyway, so
the latency is not saved — only made harder to reason about.

**gRPC for internal calls.** Genuinely faster and a legitimate industry-standard choice.
Rejected for now on two grounds: an OpenAPI document that a reviewer can read and `curl`
is worth more here than a few milliseconds per hop, and the protobuf toolchain adds
build complexity disproportionate to the benefit at this size. Worth revisiting as a
deliberate, measured comparison once the platform is complete.

**A shared database between services.** Rejected. It is the fastest route to a
distributed monolith, and it would make the ledger's append-only guarantee
unenforceable.

## Consequences

The system tolerates `accounts` or the notification path being down without failing
transfers, because those learn by event. It does not tolerate `ledger` or `risk` being
down, and that is correct — a bank that cannot screen a payment should not make it.

The cost is two integration styles to test rather than one: REST contracts are covered
by Pact, event handling by consumer tests asserting idempotent behaviour under duplicate
delivery.

Because both channels carry OpenTelemetry context — REST via headers, Kafka via message
headers — a single trace spans the whole flow regardless of which style each hop used.
