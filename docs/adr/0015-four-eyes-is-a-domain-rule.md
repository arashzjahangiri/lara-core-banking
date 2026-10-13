# 15. Four-eyes is a domain rule, not an authorisation concern

**Status:** Accepted
**Date:** 2026-10-13

## Context

Payments above a threshold need a second person to approve them. The maker may not be the checker.

This looks like an authorisation rule, and in most systems it is implemented as one: a permission
check in a filter, an annotation on a controller method, or — more often than anyone admits — a
disabled button in the UI.

## Decision

The rule lives in the `Transfer` aggregate. `approve(checker)` and `declineApproval(checker,
reason)` both raise `FourEyesViolationException` when the checker is the maker.

The REST layer maps that to `403` and otherwise stays out of it.

## Why not in the adapter

A rule that lives in an adapter is a rule that is absent from every other way into the object.

There are already three other ways in. The recovery sweep advances sagas without going near HTTP.
A message consumer will exist by Phase 5. And whatever someone writes next year to fix an incident
at two in the morning will reach for the application layer, not the REST resource.

Each of those is a path on which an authorisation-layer rule simply does not run. The aggregate is
the one place everything goes through, so it is the only place the rule is genuinely enforced
rather than merely usually enforced.

The UI-button version is worse again: it is not a control at all, it is a convenience that happens
to be shaped like one.

## Why the maker cannot decline either

Only approval is a control in the usual telling. Letting a maker decline their own request looks
harmless — they are only cancelling something they asked for.

It is not harmless. A maker who can close their own request can use the approval queue as a
drafting space: submit, look at it, withdraw, resubmit. The audit trail then stops showing a
genuine second pair of eyes on every outcome, and "every transfer over the threshold was seen by
two people" quietly becomes "every transfer that happened to survive was seen by two people".

## What this does not solve

**The checker's identity is currently supplied in the request body**, so a caller can name anyone.
The domain rule is real and enforced; the identity behind it is a placeholder until Phase 4 brings
Keycloak and it becomes the authenticated principal.

This is written down rather than left implicit because the gap is easy to miss. A reader seeing
`FourEyesViolationException` thrown from the domain could reasonably conclude the control is
complete, and today it is complete in every respect except the one that matters most in
production.

## Consequences

`startPosting()` had to be narrowed. It previously accepted `APPROVAL_PENDING` as well as
`SCREENING`, which meant the ordinary posting transition was a way to post an unapproved payment
without ever calling `approve`. A parked transfer now leaves that state through `approve` alone.

That is the shape this kind of control has to take: not an extra check bolted beside the normal
path, but the removal of every path that skips it.

Comparison is case-insensitive and trims whitespace. Those are not the attacks worth worrying
about, but they are the ways a rule like this fails by accident — two systems spelling the same
username differently is far more likely than anyone deliberately gaming it.

Approving commits in its own transaction before the saga is driven. A checker clicking approve
should not wait on three downstream services, and their decision must survive even if the posting
that follows fails — which it can, and the recovery sweep then owns the unfinished saga.
