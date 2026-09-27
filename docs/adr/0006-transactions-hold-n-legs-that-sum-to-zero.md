# 6. Transactions hold N legs that sum to zero

**Status:** Accepted
**Date:** 2026-09-29

## Context

"Double-entry" is usually taken to mean two entries, and the obvious model follows from
that reading: a transaction has a debit account, a credit account and an amount.

That model holds exactly as long as money is a closed loop between two customers. It
fails on the first fee. Sending €100.00 and charging €0.50 is not two entries — the
sender is debited €100.00, the receiver credited €99.50, and the bank's income account
credited €0.50. Three legs, and no pair of them balances on its own.

The usual workaround is to write two transactions, or to deduct the fee and not record
the other side. The first makes the fee separable from the transfer it belongs to, so a
reversal can undo one without the other. The second creates money.

## Decision

A transaction holds a list of legs. Each leg names an account, an `EntrySide` and a
positive `Money`. The invariant is that **total debits equal total credits**, checked in
the constructor, so an unbalanced transaction cannot be constructed at all.

Two legs is the minimum, not the rule.

```
DEBIT   CUSTOMER000001       EUR 100.00
CREDIT  CUSTOMER000002       EUR  99.50
CREDIT  BANK.FEE.INCOME.EUR  EUR   0.50
```

Direction is carried by the side rather than by the sign of the amount, and leg amounts
are always positive. A negative debit is ambiguous in a way that a credit is not, and the
side is how an accountant reads a ledger. `signedAmount()` applies the sign where
arithmetic needs it.

Transactions are immutable. The list of legs is defensively copied on construction and
exposed unmodifiable. A mistake is corrected by posting a contra transaction — every leg
on the opposite side — never by editing or deleting what was written.

## Alternatives considered

**A fixed debit account, credit account and amount.** Rejected, as described above: it
cannot express a fee, and every workaround either fragments an atomic movement or loses
money.

**Signed amounts with no side, summing to zero.** Slightly simpler to validate, and a
legitimate design used by several ledgers. Rejected because it makes the common case
harder to read — a list of positive and negative numbers requires the reader to reconstruct
which are debits — and because it permits a "negative debit", which has no meaning.

**Allowing a single-leg transaction.** Rejected. One leg can only balance if it is zero,
and a zero-amount posting records nothing. The minimum is genuinely two.

**Allowing multiple currencies in one transaction.** Rejected for now. A transaction
spanning currencies cannot balance without an exchange rate, and a rate is a business
decision with a source, a timestamp and a spread — none of which belong in the ledger's
balancing rule. Foreign exchange will be modelled as two single-currency transactions
against a clearing account, plus an explicit rate record.

## Consequences

Fees, taxes and FX spread all have somewhere to go from the start, so adding them later
does not reshape the ledger.

Validation happens once, at construction, and nothing downstream needs to re-check it. An
unbalanced transaction cannot reach the database because it cannot be built in memory.

Allowing more than one leg per account in a single transaction is deliberate — a sender
paying both an amount and a fee is debited twice — so code reading an account's
involvement must handle a list rather than assume one entry.

The trial balance becomes computable from the legs alone: summing every signed amount
across the whole book must give zero. That is the strongest correctness check available
here, and the N-leg model is what makes it expressible.
