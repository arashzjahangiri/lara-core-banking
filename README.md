# Lara Core Banking

A core banking platform built on Quarkus: an immutable double-entry ledger, payment
orchestration with sagas, and a clean-architecture codebase that keeps the framework
out of the domain.

> **Status: Phase 1 — walking skeleton.** See the [roadmap](#roadmap) for what exists
> today and what comes next. Built in the open from the first commit.

---

## Why this exists

Most demo projects model a bank account as a number that goes up and down. That is not
how banking works, and it hides every problem that makes financial software hard.

Lara uses **double-entry bookkeeping**. A transaction is a set of legs that must sum to
zero, every posting is immutable, and balances are *derived* from the ledger rather than
stored as a mutable counter. Customer deposits sit on the liability side of the bank's
balance sheet, because that money is owed to the customer.

That single decision forces everything else to be real:

| Because the domain requires… | …the system must implement |
| --- | --- |
| A retried payment must not move money twice | Idempotency keys, exactly-once effects over at-least-once delivery |
| A transfer spans accounts, limits and the ledger | Saga with compensating transactions — no distributed 2PC |
| Events must not publish if the transaction rolled back | Transactional outbox |
| Two transfers hit the same account at once | Optimistic locking with conflict retry |
| An auditor asks what a balance was last Tuesday | Append-only ledger, temporal queries, no destructive updates |
| Money must never be wrong by a cent | Integer minor units, explicit currency, no floating point |

These patterns are here because the problem demands them, not to demonstrate that they
are known.

## Architecture

Four services, each owning its own PostgreSQL database.

| Service | Responsibility | Why it is separate |
| --- | --- | --- |
| `ledger` | Append-only double-entry postings. The only service that may write money. | Different consistency and retention rules from everything else. Never deletes. |
| `accounts` | Customers, accounts, IBANs, KYC state. | Read-heavy and slow-changing; a completely different access pattern from the ledger write path. |
| `payments` | Orchestrates transfers: validate, screen, post, confirm or compensate. | Holds workflow state, not money. |
| `risk` | Limits, velocity rules, sanctions screening. | Rules change on their own cadence; in a real bank this is a separate team. |

A thin **BFF** serves the UI and terminates authentication. It contains no business logic.

**Deliberately not included:** no service-discovery server, no config server, no
service-mesh, no API-gateway product. On Kubernetes, DNS is service discovery,
ConfigMaps are configuration and an Ingress is the gateway. Adding more on top of that
would be ceremony, not architecture.

### Clean architecture, and what it costs

Every service is a Maven multi-module build with dependencies pointing strictly inward.
The **build** enforces the boundary, not discipline — a module cannot use a framework it
does not depend on.

```
<service>/
├── domain/          no dependencies at all. Not Quarkus, not Jakarta, not even JSON.
├── application/     depends on domain only. Use cases and ports, annotation-free.
├── infrastructure/  depends on application + domain + Quarkus. Adapters, JPA, Kafka, REST.
└── bootstrap/       depends on everything. The Quarkus application and its wiring.
```

Quarkus lives in `infrastructure/` and `bootstrap/` and nowhere else. Use cases take
their dependencies through the constructor, so every one of them can be built with `new`
in a test. The two worlds meet in exactly one place — a CDI producer in `bootstrap/`.

This is honest duplication: a pure `Transaction` in `domain/` and a `TransactionEntity`
with `@Entity` in `infrastructure/`, plus a mapper between them. It buys a domain that
unit-tests in milliseconds with no container. It is the right trade for a system whose
point is architecture, and the wrong trade for a CRUD application on a deadline.

It also rules out Panache, Quarkus's active-record layer. Panache is excellent and would
be the right call in most projects, but it puts persistence methods on the domain object,
which is the precise thing this structure exists to prevent.

The rule is enforced by a test rather than a convention:

```java
@ArchTest
static final ArchRule application_is_framework_free =
    noClasses().that().resideInAnyPackage("..domain..", "..application..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("..infrastructure..", "jakarta..", "io.quarkus..",
                            "io.smallrye..", "org.hibernate..");
```

## Stack

| Concern | Choice |
| --- | --- |
| Language / runtime | Java 21, Quarkus 3.x |
| Specifications | Jakarta REST, CDI, Bean Validation, Jakarta Persistence |
| Database | PostgreSQL, one per service |
| Migrations | Flyway |
| Messaging | Apache Kafka via SmallRye Reactive Messaging |
| Reliable publishing | Transactional outbox, captured by Debezium |
| Identity | Keycloak (OpenID Connect) |
| Mapping | MapStruct |
| Tracing / metrics | OpenTelemetry, Micrometer → Prometheus → Grafana |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers, ArchUnit, Pact |
| UI | React + TypeScript, MUI, served via Quarkus Quinoa |
| Deployment | Docker, Kubernetes, Helm, GraalVM native image |
| CI | GitHub Actions |

## Roadmap

| Phase | Contents | Status |
| --- | --- | --- |
| 1 | Walking skeleton: `ledger` service, chart of accounts, N-leg postings, tests, CI | In progress |
| 2 | `accounts` service, Kafka, transactional outbox, reversals, hash-chained audit trail | Planned |
| 3 | `payments` and `risk`, saga with compensation, scheme routing, four-eyes approval | Planned |
| 4 | Keycloak, OIDC across services, BFF, React UI | Planned |
| 5 | Kubernetes, Helm, observability, native image benchmarks | Planned |

## Getting started

Not yet runnable — Phase 1 is in progress. This section will carry a
`docker compose up` that works from a clean clone.

## Decisions

Architecture decision records live in [`docs/adr/`](docs/adr/). Each one states the
alternatives that were considered and why they were not chosen.

## License

[MIT](LICENSE)
