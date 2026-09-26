# 3. Orchestrated saga rather than choreography

**Status:** Accepted
**Date:** 2026-09-26

## Context

A transfer spans four services and several database transactions: validate the accounts,
screen against limits and sanctions, post to the ledger, confirm. There is no distributed
transaction available — two-phase commit across independent services with their own
databases is neither practical nor desirable — so the workflow has to be a saga with
compensating actions.

Sagas come in two shapes. In **choreography**, each service reacts to events and emits
its own; nobody is in charge. In **orchestration**, one service owns the workflow and
calls the others.

Choreography is the more decoupled design, and on paper the more attractive one.

## Decision

`payments` orchestrates. It owns an explicit state machine for each transfer, drives the
calls to `accounts`, `risk` and `ledger`, and issues compensating actions when a step
fails after money has moved.

## Alternatives considered

**Choreography.** Rejected, on operational rather than architectural grounds.

With choreography the workflow is not written down anywhere. It exists as an emergent
property of which service happens to subscribe to which topic. That has two consequences
this domain cannot absorb:

1. When a customer asks where their transfer is, no single service can answer. The state
   must be reconstructed by correlating logs across four services.
2. Changing the sequence means editing subscriptions in several codebases, and nothing
   fails if a step is accidentally dropped — the transfer simply stops, silently.

For money movement, a state machine that can be queried with one `GET` is worth the
coupling it costs.

**A workflow engine such as Temporal or Camunda.** Genuinely good, and what several banks
actually run. Rejected here because the point of this project is to show the mechanics —
state transitions, compensation, idempotency — and delegating them to an engine hides
exactly the reasoning a reviewer wants to see. A production system at scale should
reconsider this.

## Consequences

`payments` is coupled to the shape of the workflow, and a new step means changing it.
That is accepted: the workflow is business logic, and having one place that owns it is a
feature rather than a leak.

The state machine is queryable, which makes two things possible that choreography would
not: a support answer to "where is this transfer", and the saga-status screen in the UI,
which is the clearest demonstration in the project that this is a distributed system
rather than a monolith with extra deployments.

Compensation must be explicit and tested. A transfer that fails after posting to the
ledger is corrected by posting a reversing contra entry — never by deleting or editing
the original. The mechanics of reversal get their own record when that work starts.

`payments` holds workflow state, not money. It must never be the service that decides a
balance.
