# 1. Record architecture decisions

**Status:** Accepted
**Date:** 2026-09-26

## Context

This project exists to demonstrate architectural judgement, not just working code. A
reader arriving at the repository needs to see which alternatives were considered and
why they were rejected — that reasoning is invisible in a diff.

## Decision

Significant decisions are recorded as short Architecture Decision Records in
`docs/adr/`, numbered sequentially and never rewritten once accepted. Superseding a
decision means adding a new record that references the old one, not editing history.

Each record states the context, the decision, the alternatives rejected and the
consequences — including the costs, which are the part usually omitted.

## Consequences

Decisions are reviewable. A reader can follow how the design arrived where it did.
The cost is discipline: a decision made and not written down is worse than no process,
because the index becomes misleading.
