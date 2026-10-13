# Lara Core Banking

[![Build](https://github.com/arashzjahangiri/lara-core-banking/actions/workflows/build.yml/badge.svg)](https://github.com/arashzjahangiri/lara-core-banking/actions/workflows/build.yml)

A core banking platform built on Quarkus: an immutable double-entry ledger, payment
orchestration with sagas, and a clean-architecture codebase that keeps the framework out
of the domain.

> **Phases 1 and 2 complete.** Two services, 347 tests, and a `docker compose up` that works from
> a clean clone. The `ledger` posts balanced double-entry transactions and publishes them through
> a transactional outbox; `accounts` consumes those events into a balance read model, idempotently.
> See the [roadmap](#roadmap) for what comes next.

---

## Why this exists

Most demo banking projects model an account as a number that goes up and down. That is not
how banking works, and it hides every problem that makes financial software hard.

Lara uses **double-entry bookkeeping**. A transaction is a set of legs that must sum to
zero, every posting is immutable, and balances are *derived* from the ledger rather than
stored as a counter. A customer's deposit sits on the **liability** side of the bank's
balance sheet, because that money is owed to the customer.

That single decision forces everything else to be real:

| Because the domain requires… | …the system implements |
| --- | --- |
| A retried payment must not move money twice | Caller-supplied idempotency key, unique-constrained, race resolved by the database |
| A transfer carrying a fee is not two entries | N-leg transactions, balanced at construction |
| An auditor asks what a balance was last Tuesday | Append-only ledger, balances folded from postings at any instant |
| Nobody may quietly rewrite history | `UPDATE` and `DELETE` refused by a database trigger |
| Money must never be wrong by a cent | Integer minor units, exact allocation, no floating point anywhere |
| Events must not publish if the transaction rolled back | Transactional outbox, captured by Debezium |
| A transfer spans accounts, limits and the ledger | Saga with compensation *(Phase 3)* |

These patterns are here because the problem demands them, not to demonstrate that they
are known. Each one is argued in an [architecture decision record](docs/adr/), including
the alternatives that lost.

## Getting started

Requires Docker. Nothing else — the build runs in a container and the Maven wrapper needs
no local Maven.

```bash
git clone https://github.com/arashzjahangiri/lara-core-banking.git
cd lara-core-banking
docker compose up --build
```

Then post a funding transaction — the bank's cash rises, and it now owes the customer the
same amount:

```bash
curl -i -X POST http://localhost:8080/ledger/transactions \
  -H 'Content-Type: application/json' \
  -d '{
        "reference": "DEMO-001",
        "legs": [
          {"account": "BANK.CASH.EUR",  "side": "DEBIT",  "amountMinorUnits": 50000, "currency": "EUR"},
          {"account": "CUSTOMER000001", "side": "CREDIT", "amountMinorUnits": 50000, "currency": "EUR"}
        ]
      }'
```

`201 Created`. **Send it again and you get `200 OK` with the same transaction** — the
reference made it idempotent, and the money moved once.

A transfer carrying a fee is three legs, which is the case a two-entry model cannot
express:

```bash
curl -s -X POST http://localhost:8080/ledger/transactions \
  -H 'Content-Type: application/json' \
  -d '{
        "reference": "DEMO-002",
        "legs": [
          {"account": "CUSTOMER000001",      "side": "DEBIT",  "amountMinorUnits": 10000, "currency": "EUR"},
          {"account": "CUSTOMER000002",      "side": "CREDIT", "amountMinorUnits": 9950,  "currency": "EUR"},
          {"account": "BANK.FEE.INCOME.EUR", "side": "CREDIT", "amountMinorUnits": 50,    "currency": "EUR"}
        ]
      }'

curl -s http://localhost:8080/ledger/accounts/CUSTOMER000001/balance
curl -s "http://localhost:8080/ledger/accounts/CUSTOMER000001/balance?asOf=2026-01-01T00:00:00Z"
```

The books balance exactly:

```
assets 500.00  =  liabilities (400.00 + 99.50)  +  income 0.50
```

Port 8080 already taken? `LEDGER_PORT=8081 docker compose up`.
OpenAPI is at `/q/openapi`, health at `/health/ready`.

### Watch the events

Every posting is published to Kafka, keyed by account:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic ledger.account \
  --from-beginning --property print.key=true
```

```
"CUSTOMER000001"      => {"legs":[{"side":"DEBIT","amountMinorUnits":10000,...}], "reference":"DEMO-002", ...}
"CUSTOMER000002"      => {"legs":[{"side":"CREDIT","amountMinorUnits":9950,...}], "reference":"DEMO-002", ...}
"BANK.FEE.INCOME.EUR" => {"legs":[{"side":"CREDIT","amountMinorUnits":50,...}],   "reference":"DEMO-002", ...}
```

**The ledger never writes to Kafka.** It inserts a row into an `outbox` table in the *same*
database transaction as the postings, and Debezium publishes what committed by reading the
write-ahead log. There is no ordering of "write then publish" that survives a crash, so the
problem is removed rather than mitigated. ([ADR-0004](docs/adr/0004-transactional-outbox-for-event-publishing.md))

Try stopping it mid-flight — `docker compose stop connect`, post a few transactions, then
`docker compose start connect`. Nothing is lost: the replication slot resumes exactly where
it left off. Delivery is **at least once**, so consumers must deduplicate on event id.

## Architecture

Four services, each owning its own PostgreSQL.

| Service | Responsibility | Status |
| --- | --- | --- |
| `ledger` | Append-only double-entry postings. The only service that may write money. | **Built** |
| `accounts` | Customers, accounts, IBANs, KYC state, balance read model. | **Built** |
| `payments` | Orchestrates transfers: validate, screen, post, confirm or compensate. | **Built** |
| `risk` | Limits, velocity rules, sanctions screening. | **Built** |

Services talk two ways, and the rule is **ask synchronously, tell asynchronously** — a
REST call when the caller needs an answer to proceed, an event when it has a fact nobody
is waiting on. ([ADR-0002](docs/adr/0002-synchronous-commands-asynchronous-facts.md))

### How a transfer actually moves

`payments` owns the workflow explicitly rather than letting it emerge from who happens to
subscribe to what. Every outbound call lives in one file, and the order they happen in is the
order they are written. ([ADR-0003](docs/adr/0003-orchestrated-saga-rather-than-choreography.md))

```
REQUESTED ──▶ SCREENING ──┬──▶ POSTING ──┬──▶ POSTED ──┬──▶ COMPLETED
                          │              │             │
                          │              └──▶ REJECTED └──▶ COMPENSATING ──▶ COMPENSATED
                          │                                      │
                          ├──▶ APPROVAL_PENDING ──▶ POSTING      └──▶ FAILED
                          │
                          └──▶ REJECTED
```

| Step | Calls | On failure |
| --- | --- | --- |
| Validate the accounts | `accounts` | reject — nothing moved |
| Screen the transfer | `risk` | reject, or park for a second person |
| Post the entry | `ledger` | reject if refused; **stay put** if unreachable |
| Submit to the scheme | SEPA only | **compensate** — the money already moved |

Two properties of that diagram carry most of the safety. **`REJECTED` is unreachable once money
has moved**, so everything downstream can read it as "nothing happened" without further checking.
And **`POSTED` cannot be abandoned** — its only exits are finishing and reversing, because a saga
that can walk away while the ledger holds the money is a saga that loses it. The state machine
enforces both; no caller has to remember.

Each step commits in its own transaction and the state is saved *before* the next call goes out.
A crash during the risk call therefore leaves the saga in `SCREENING` — a state that says exactly
which call was in flight — rather than in `REQUESTED`, which would claim nothing had been
attempted. A scheduled sweep picks up anything left and re-drives it, which is only safe because
every downstream call is idempotent.

The hardest distinction in the whole phase is between **a decision and an outage**. A risk service
that says "blocked" has decided something; one that times out has decided nothing. Conflating them
either rejects every valid transfer for the length of an outage or lets unscreened payments
through, so an unreachable dependency is neither — the saga simply stops where it is.
([ADR-0016](docs/adr/0016-no-fallback-may-fabricate-a-success.md))

**Deliberately not included:** no service-discovery server, no config server, no service
mesh, no API-gateway product. On Kubernetes, DNS is service discovery, ConfigMaps are
configuration and an Ingress is the gateway.

### Clean architecture, and what it costs

Every service is a Maven multi-module build with dependencies pointing strictly inward.
The **build** enforces the boundary, not discipline — a module cannot use a framework it
does not depend on.

```
services/ledger/
├── domain/          no dependencies at all. Not Quarkus, not Jakarta, not even JSON.
├── application/     depends on domain only. Use cases and ports, annotation-free.
├── infrastructure/  depends on application + domain + Quarkus. Adapters, JPA, REST.
└── bootstrap/       depends on everything. The Quarkus application and its wiring.
```

Quarkus lives in `infrastructure/` and `bootstrap/` and nowhere else. Use cases take their
dependencies through the constructor, so every one can be built with `new` in a test. The
two worlds meet in exactly one place — a CDI producer in `bootstrap/`.

This is honest duplication: a pure `LedgerTransaction` in `domain/` and a
`LedgerTransactionEntity` with `@Entity` in `infrastructure/`, plus mapping between them.
It buys a domain that unit-tests in **under a second** with no container. It is the right
trade for a system whose point is architecture, and the wrong trade for a CRUD service on
a deadline. ([ADR-0008](docs/adr/0008-quarkus-confined-to-the-outer-layers.md))

The rule is enforced by a test, not a convention:

```java
@ArchTest
static final ArchRule domain_and_application_are_framework_free = noClasses()
        .that().resideInAnyPackage("..ledger.domain..", "..ledger.application..")
        .should().dependOnClassesThat().resideInAnyPackage(
                "..infrastructure..", "jakarta..", "io.quarkus..", "io.smallrye..", "org.hibernate..");
```

## Testing

| Layer | What it covers | Count |
| --- | --- | --- |
| Domain | Accounting invariants, money arithmetic, balance derivation. No container. | 127 |
| Application | Use cases, driven by hand-written fakes. No mocking framework. | 24 |
| Architecture | Layer dependencies, no floating point, no legacy date types. | 6 |
| Integration | Real HTTP against a real PostgreSQL via Dev Services, including the outbox. | 36 |
| Contract | Consumer-driven Pact between `accounts` and `ledger`, verified both ways. | 2 |
| Concurrency | Parallel transfers, a sweep racing the orchestrator, one key from many threads. | 13 |
| Fault injection | A fault at every saga step; the books must balance after each one. | 15 |

Three are worth singling out.

**The trial balance.** After posting, every debit and every credit across the whole
database is summed. If they ever differ, money has been created or destroyed. A second
assertion checks each transaction balances individually — which catches two broken
transactions whose errors cancel out.

**The append-only guarantee**, tested through raw JDBC rather than Hibernate. Through the
ORM it would prove only that the mapping refuses; through a plain connection it proves the
*database* refuses, which is what protects the ledger from a migration, a console session
or another service.

```
UPDATE posting_leg  →  posting_leg is append-only; UPDATE is not permitted.
                       Post a contra entry instead.
```

**What the tests cannot check, and the script that does.** 670 tests were green when the stack
came up for the first time, and two bugs appeared within minutes: payments was configured to post
to two ledger accounts that do not exist, and its REST clients pointed at `localhost`, which
inside a container means the container itself. Neither is a logic error — both are values that are
plausible, type-correct and wrong, and both are facts about the *gap between* services that no
test running inside one of them can see. `scripts/verify-stack.ps1` drives the four real services
and reads the ledger''s own database for its assertions. ([ADR-0017](docs/adr/0017-cross-service-configuration-needs-a-live-stack.md))

```
./scripts/verify-stack.ps1        # after docker compose up -d --build

  OK   the transfer completed
  OK   the debtor paid the amount plus the fee
  OK   the trial balance is still zero
  OK   a replay returns the original transfer, not a new one
```

**The concurrency suite, and the fake it runs against.** Ten transfers race for an account
holding four transfers' worth of money, while a recovery sweep runs at the same instant. The
point is not the threads — it is that the ledger stand-in *keeps books*: entries must sum to
zero, an account cannot go below zero, and one reference yields one transaction. A test against a
permissive stub proves nothing, because "no transfer overdrew the account" would then be a
statement about the stub and would pass just as happily with the locking removed. Two tests point
at the fake itself to check it refuses what it claims to refuse.

## Roadmap

| Phase | Contents | Status |
| --- | --- | --- |
| 1 | `ledger` service, chart of accounts, N-leg postings, idempotency, tests, CI, Compose | **Done** |
| 2 | Outbox via Debezium, `accounts` service, balance read model, reversals, hash-chained audit, business calendar, Pact contracts | **Done** |
| 3 | `payments` and `risk`, saga with compensation, scheme routing, four-eyes approval, crash recovery, resilience | **Done** |
| 4 | Keycloak and OIDC, BFF, React and MUI front end including the saga status view | Planned |
| 5 | Kubernetes and Helm, OpenTelemetry, Prometheus, Grafana, native image benchmarks | Planned |

## What I would do differently at scale

Naming a design's limits is more useful than pretending it has none.

**Balances are folded from every posting, every time.** Correct, and it makes point-in-time
queries free, but it is O(postings) per account. At real volume this needs periodic
balance snapshots — fold from the last snapshot forward rather than from the beginning —
which trades the purity for a cache that must be proven correct against the ledger.

**The ledger is one PostgreSQL instance.** It will go a long way, and further than most
people expect, but a bank eventually shards by account range or moves the write path to an
append-optimised store. The append-only model makes that easier than it would otherwise
be; the trial-balance query makes it harder, because it is inherently global.

**The saga is orchestrated in application code.** Deliberate, because the point here is to
show the mechanics — state transitions, compensation, idempotency. A production system at
this complexity would likely use a workflow engine such as Temporal and get durability and
observability for free. ([ADR-0003](docs/adr/0003-orchestrated-saga-rather-than-choreography.md))

**There is no authentication yet.** Phase 4. Everything is currently open, which is fine
for a local stack and would not be fine anywhere else.

**The hash chain detects tampering, it does not prevent it.** Anyone able to rewrite rows can
recompute the chain forward. Stopping that needs the head hash published somewhere the operator
does not control. ([ADR-0012](docs/adr/0012-hash-chained-ledger.md))

**Appending takes a row lock on the chain head.** One verifiable sequence costs a serialised
write path. Per-account chains would remove the lock and give up the global order — the obvious
move if it ever becomes the bottleneck.

**Single currency per transaction.** Foreign exchange needs two transactions against a
clearing account plus an explicit rate record, which is not yet
built. ([ADR-0006](docs/adr/0006-transactions-hold-n-legs-that-sum-to-zero.md))

## Building locally

The Maven wrapper needs no local Maven. JDK 21 is required.

```bash
./mvnw clean verify          # everything, including integration tests (needs Docker)
./mvnw clean verify -pl services/ledger/domain        # the fast suite, no Docker
./mvnw quarkus:dev -pl services/ledger/bootstrap      # live reload, Dev Services Postgres
./mvnw process-sources       # apply formatting; CI fails if this would change anything
```

`clean` is not optional in CI. Maven's incremental compiler will skip recompiling a
changed file on a warm tree, which once let a deliberate architecture violation compile
and pass.

## Decisions

Architecture decision records live in [`docs/adr/`](docs/adr/). Each states the
alternatives considered and why they were not chosen — including the costs, which are the
part usually left out.

## License

[MIT](LICENSE)
