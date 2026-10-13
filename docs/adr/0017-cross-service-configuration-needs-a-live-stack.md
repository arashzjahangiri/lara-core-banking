# 17. Cross-service configuration needs a live stack to verify

**Status:** Accepted
**Date:** 2026-10-13

## Context

Phase 3 finished with 670 passing tests across five layers: domain, application, architecture,
integration against real PostgreSQL, contract, and concurrency. Every one of them was green.

Then the compose stack was brought up for the first time, and two bugs appeared within minutes.

**The payments service posted to ledger accounts that do not exist.** It was configured for
`BANK.SEPA.SUSPENSE.EUR` and `BANK.INCOME.FEES.EUR`; the ledger's chart of accounts has
`BANK.CLEARING.EUR` and `BANK.FEE.INCOME.EUR`. The ledger refuses a posting to an unknown account,
so every SEPA transfer would have failed in production.

**Its REST clients pointed at `localhost`.** That is correct when the four services run side by
side on a developer's machine and meaningless inside a container, where `localhost` is the
container itself. Every transfer stalled on the first outbound call.

Neither is a logic error. Both are values that are plausible, type-correct, present in
configuration, and wrong.

## Why the tests could not catch either

They are facts about the *gap between* services, and a test that runs inside one service has no
access to the other side of that gap.

The account names were checked against a ledger stand-in with no chart of accounts, so it accepted
any string. A fake cannot tell a real account from a plausible invented one, because it does not
know what real means. The URLs were checked against a JVM where `localhost` genuinely is the right
answer.

This is not a gap in the fakes that better fakes would close. A fake faithful enough to catch both
would have to contain the other service's seed data and the deployment's network topology — at
which point it is not a fake, it is the other service.

## Decision

A scripted end-to-end verification, `scripts/verify-stack.ps1`, run against the compose stack. It
drives the four real services over HTTP and then reads the **ledger's own database** for its
assertions.

It checks what only a live stack can:

- that payments posts to accounts the ledger actually has
- that the services reach each other as configured
- that the real trial balance is zero after money has moved, and again after it has been reversed
- that a compensated transfer leaves two transactions, with nothing deleted or edited

## What the fakes were changed to cover

Not everything has to wait for a stack. `InMemoryLedger` now carries the chart of accounts and
refuses a posting outside it, so a wrong account name fails in CI from now on rather than at the
next deployment.

That is the right split. **Anything a fake can be made faithful about, it should be.** The live
stack is for what remains: configuration that crosses a boundary, and data the other service owns.

## Consequences

The verification is a script rather than a test, and it is checked in rather than kept in someone's
shell history. Running it needs a stack, so it is not part of `mvn verify` — but it is repeatable
by anyone, which the ad-hoc version it replaced was not.

Compensation cannot be reached by accident, because in normal operation the scheme accepts
everything. `PAYMENTS_REJECTED_IBANS` makes the simulated scheme refuse a named beneficiary, which
is the only way to watch that code path run. The script skips the compensation section and says so
when the variable is unset, rather than silently passing a check it never performed.

There is a wider lesson about health checks. The misconfigured service reported itself **healthy**
throughout, because ADR-0016 deliberately does not fail readiness when a dependency is unreachable
— every instance would go unready together and one dependency's outage would become a total
outage. That decision stands. But it means a misconfigured service looks perfectly healthy until
somebody sends it a payment, and the circuit-breaker states published on `/health/ready` are what
would actually have shown it. Publishing them as data is not decoration.
