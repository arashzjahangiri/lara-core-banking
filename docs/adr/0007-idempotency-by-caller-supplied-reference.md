# 7. Idempotency by caller-supplied reference

**Status:** Accepted
**Date:** 2026-09-29

## Context

Retries are not an edge case. A client times out and re-sends, a saga step is
re-executed after a crash, Kafka delivers the same message twice because at-least-once
is the only guarantee it offers. Any of these can reach the ledger twice with the same
intent.

For almost any other service a duplicate is a nuisance. For a ledger it is the one
failure that must never happen: the money moves twice, the balance is wrong, and because
postings are append-only the correction is itself another visible transaction.

Nothing in the posting path prevented it.

## Decision

Every posting carries a `TransactionReference` supplied by the **caller**, and the ledger
treats it as the key of the operation.

- A reference never seen before posts normally.
- A reference already recorded, with **identical legs**, returns the original transaction
  and writes nothing. The caller cannot tell a retry from the first call, which is the
  point.
- A reference already recorded, with **different legs**, is refused with
  `PostingRejectedException.ReferenceReused`.

The reference must come from the caller. One the ledger generated would differ on every
attempt and identify nothing, so the caller derives it from something stable about the
request — a payment id, a scheme message id.

A `TransactionReference` is deliberately a different type from `TransactionId`. The
reference is the caller's name for the intent; the id is the ledger's name for what it
recorded. One reference maps to exactly one id, permanently.

The check runs **before** the account, currency and limit checks. A retry is answered
from what was already recorded, so tightening a posting limit cannot strand a transfer
that was accepted under the old one — the first call already succeeded and the retry only
asks what happened.

## Alternatives considered

**Deduplicate on the content of the legs.** Needs no extra field, and is wrong: two
genuinely separate transfers of the same amount between the same accounts are
indistinguishable from a retry. The system would silently drop the second, which is the
same class of bug as posting twice, in the other direction.

**A ledger-generated id returned to the caller, which it must quote on retry.** Correct,
and the pattern behind some payment APIs. Rejected because it needs a round trip before
the operation is safe: a caller that times out before receiving the id has nothing to
quote, and that is exactly when a retry happens.

**Treating a reused reference with different legs as a new posting.** Rejected. It makes
the reference meaningless as a key and hides a real caller bug.

**Returning the original silently when the legs differ.** Rejected for the same reason,
and worse: it drops a movement the caller believes was recorded.

## Consequences

Posting is safe to retry, which is what lets the saga in Phase 3 retry a ledger step
without reasoning about whether the previous attempt landed.

Callers must choose references deliberately. A reference reused across genuinely
different movements now fails loudly, which is a behaviour change for any caller that was
careless, and the right one.

This covers the sequential case only. Two concurrent posts of the same reference can both
see "not found" and both proceed, so the database needs a unique constraint on the
reference and the adapter must catch the violation and answer with the original. That
belongs with the persistence adapter, where the constraint lives, and is noted on the
relevant ticket.
