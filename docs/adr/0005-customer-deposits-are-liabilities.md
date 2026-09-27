# 5. Customer deposits are liabilities

**Status:** Accepted
**Date:** 2026-09-29

## Context

Nearly every demonstration banking project models an account as a number that goes up
and down. Deposit money, the number rises; send money, it falls. It works, it is easy to
reason about, and it is not what a bank does.

When a customer deposits money, the bank takes ownership of the cash and records an
obligation to give it back on demand. The deposit is therefore something the bank
**owes** — a liability on the bank's own balance sheet — while the cash it now holds is
an **asset**. The two entries are what keep the accounting equation true:

```
ASSET  =  LIABILITY  +  EQUITY  +  (INCOME - EXPENSE)
```

Without that distinction there is nowhere to put a fee. A transfer between two customers
is a closed loop and balances trivially, but the moment the bank charges for it, the fee
has to be credited to an account the bank owns. A ledger with only customer accounts
cannot express that, and the usual workaround — deducting the fee and quietly not
recording the other side — is precisely the bug that makes a ledger stop balancing.

## Decision

Every ledger account carries an `AccountClass`: `ASSET`, `LIABILITY`, `EQUITY`, `INCOME`
or `EXPENSE`. Customer deposits are `LIABILITY`.

Each class declares the side its positive balance sits on, and that determines whether a
posting raises or lowers a balance:

| Class | Normal side | Raised by | Example |
| --- | --- | --- | --- |
| `ASSET` | Debit | Debit | Cash at the central bank |
| `LIABILITY` | Credit | Credit | **A customer's current account** |
| `EQUITY` | Credit | Credit | Retained earnings |
| `INCOME` | Credit | Credit | Fees earned |
| `EXPENSE` | Debit | Debit | Scheme charges paid |

Debit and credit are not synonyms for increase and decrease. A debit raises an asset and
lowers a liability. Encoding that once, in `AccountClass`, means no posting code has to
remember it.

The bank's own accounts are enumerated in `SystemAccount` — cash, fee income, clearing
and suspense — and resolve to one account per currency, so the database seed iterates the
enum rather than repeating a hand-written list that could drift.

## Alternatives considered

**A signed balance with no account class.** Simplest, and adequate until the first fee.
Rejected: it cannot represent the bank's own side of a transaction, so income and
expenses have nowhere to go, and a trial balance becomes impossible to compute. Since the
trial balance is the strongest correctness test available here, giving it up would be
expensive.

**Separate `DebitAccount` and `CreditAccount` types.** Encodes the normal side in the
type system rather than in an enum. Rejected as the wrong axis: the normal side is a
consequence of the class, not an independent property, and the split would duplicate
every operation while still not distinguishing income from equity.

**A full general-ledger chart with numeric account codes** (1000–1999 assets, and so on).
This is what a real core banking system uses. Deferred rather than rejected: the five
classes carry the accounting semantics that matter here, and numeric ranges add
bookkeeping ceremony without changing any invariant. Worth revisiting if reporting is
ever added.

## Consequences

A trial balance becomes possible and meaningful: summing every debit and every credit
across the database must produce equal totals, and the sum of all balances must be zero.
That single assertion catches any error that creates or destroys money, and it is the
most valuable test in the project.

Fees, interest and FX spread all have a correct destination from the start, so adding
them later does not require reshaping the ledger.

The cost is that every account must be classified at creation and the classification
cannot change afterwards — reclassifying an account would silently reinterpret its
history. Accounts are opened with a class and closed, never converted.

Balances are also never stored. A balance is derived by folding the postings that
reference the account, which is what allows a balance to be stated as at any past instant
rather than only as of now.
