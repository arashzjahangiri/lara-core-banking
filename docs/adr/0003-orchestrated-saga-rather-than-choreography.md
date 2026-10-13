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

## Afterword, Phase 3

This record was written before any of it existed. Phase 3 built it, so here is what the decision
looked like from the other side.

### Where it lives

`TransferOrchestrator` in `services/payments/application`. Every outbound call the saga makes is
in that one file, and the order they happen in is the order they are written — which is what this
record was arguing for, and it does read that way.

`advanceOnce` performs exactly one step and returns rather than looping. That turned out to matter
more than expected: it is what lets each step commit in its own transaction, and a step spanning
two transactions can leave a saga in a state no column records.

### What it got right

The claim that a queryable state machine would pay for its coupling held up. `GET
/payments/transfers/{id}` answers "where is my transfer" with the current state *and* the history
that produced it. The compensated case is the one that justifies it: a customer whose money left
and came back can see both facts and their order, which under choreography would have meant
correlating logs across four services.

### What it missed

**There was no step that could fail after the money moved.** The record talks confidently about
compensating actions, but the saga as first built went `POSTED → COMPLETED` immediately, so
compensation had nothing to trigger it. The path existed and could never run.

The fix was a settlement step: a SEPA transfer is submitted to the scheme after the ledger
posting, and the scheme can decline. That is a real "failed after money moved" case rather than a
synthetic one. An internal book transfer skips it — both accounts are at this bank, so the ledger
entry *is* the settlement.

Worth noticing what the mistake was. The record described compensation as a property of the saga,
when it is really a property of having a step that commits something outside your own database.
Without such a step there is nothing to compensate, and the machinery is decoration.

### What it understated

"Compensation must be explicit and tested" turned out to be the smaller half. The harder question
is **what a failure means**, and the answer is not uniform:

- Before the posting, failure means rejection — nothing moved.
- After the posting, failure means compensation — something moved and must move back.
- A dependency being unreachable means *neither*. The saga stays where it is, because not knowing
  is not a decision. ADR-0016 covers this.

That third case is absent from the original record and is the one most likely to be got wrong,
because the convenient thing to do with an unreachable dependency is to assume an answer.

### What it did not anticipate

Four separate concerns turned out to be load-bearing, and each needed its own record:

- [ADR-0013](0013-idempotency-keys-at-the-api-edge.md) — the ledger's posting reference does not
  protect the saga; a retried `POST` starts a second one.
- [ADR-0014](0014-optimistic-locking-for-saga-state.md) — the orchestrator and the recovery sweep
  race each other routinely.
- [ADR-0015](0015-four-eyes-is-a-domain-rule.md) — a control enforced in an adapter is absent from
  every other way in.
- [ADR-0016](0016-no-fallback-may-fabricate-a-success.md) — the resilience policy, and why a
  fallback here is dangerous rather than merely unhelpful.

The recovery sweep in particular is missing from the original record entirely, and it is what makes
orchestration survivable: an orchestrator that owns the workflow also owns every saga it abandons
when its pod restarts.
