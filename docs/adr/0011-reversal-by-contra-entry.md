# 11. Reversal by contra entry

**Status:** Accepted
**Date:** 2026-10-01

## Context

Mistakes happen. A payment is posted twice, a fee is charged in error, a saga step fails
after the money has already moved. The ledger needs a way to put it right.

The obvious implementations are to update the offending rows or delete them. Both are
unavailable here — `UPDATE` and `DELETE` on `ledger_transaction` and `posting_leg` are
refused by a database trigger — but the question is why that restriction is right rather
than merely present.

## Decision

A transaction is corrected by posting a **contra transaction**: a new transaction whose
legs are the original's with each side flipped. Nothing is edited and nothing is removed.

The reversal's reference is derived from the original's by appending `:reversal`. Three
things follow from that one choice:

- Reversing twice is idempotent by construction, because the second attempt collides with
  a reference already recorded.
- The reversal is traceable to its original without a foreign key.
- A reversal is itself recognisable, so **reversing a reversal is refused**. Restoring the
  original movement is a new posting, not an undo, and should be asked for deliberately.

Reversals are ordinary postings in every other respect: balanced at construction,
published to the outbox, and subject to the same validation.

## Alternatives considered

**Update the original rows.** The intuitive fix, and the reason the trigger exists. It
destroys the only evidence that the original movement happened, so a statement already
sent to a customer can no longer be reconciled against the ledger, and an auditor asking
"what did you tell them in March" has no answer. It also makes the balance at any past
instant unreproducible, which is the property ADR-0005 exists to protect.

**Delete the transaction.** Worse. Everything above, plus a gap in the identity sequence
that looks like data loss.

**A `reversed` flag on the original, with balances filtering on it.** Preserves history,
and is used by some systems. Rejected because it makes every balance query carry a
condition that is easy to forget, and because the reversal then has no timestamp of its
own — the books would show the money never moved, rather than that it moved and came
back. Those are different facts, and a bank needs the second.

**A dedicated `reversal_of` foreign key instead of a derived reference.** Cleaner to
query, and worth adding later if reversals need to be listed. Rejected for now because
the derived reference gives idempotency for free, and a foreign key would need its own
uniqueness constraint to achieve the same thing.

## Consequences

Every correction is visible. The ledger shows the original movement, the reversal and the
instant of each, and the net effect on each account is zero — which is asserted by a test
rather than assumed.

The transaction count grows faster than the number of distinct business events, since a
corrected payment occupies two rows. That is the intended trade: storage is cheap and
history is not reconstructible once discarded.

`POST /ledger/transactions/{id}/reversal` returns 201 when it records the reversal and
409 when one already exists. A caller cannot accidentally double-reverse by retrying.

Phase 3's saga compensation will use this directly rather than inventing its own
mechanism — a failed step after a posting is corrected the same way a human error is.
