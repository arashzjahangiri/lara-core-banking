# 10. Money as integer minor units

**Status:** Accepted
**Date:** 2026-10-01

## Context

Money needs a representation that is exact, cheap, and impossible to use wrongly by
accident. Three candidates are in common use: a floating-point type, `BigDecimal`, and a
whole number of the currency's smallest unit.

Floating point is disqualified immediately. `0.1 + 0.2` is not `0.3` in binary, and a
ledger that accumulates that error stops balancing — slowly, and in a way that looks like
a rounding policy problem rather than a representation problem.

That leaves `BigDecimal` and minor units.

## Decision

`Money` is a record of a `long` count of minor units plus a `java.util.Currency`. Cents
for EUR, pence for GBP, whole yen for JPY. `Money.of(1234, "EUR")` is €12.34.

The scale comes from the currency itself, through `Currency.getDefaultFractionDigits()`,
so JPY renders as `JPY 1234` rather than `JPY 12.34`. A currency with no minor unit —
`XXX`, `XAU` — is rejected at construction.

Arithmetic uses `Math.addExact` and friends, so overflow throws rather than wrapping. A
silently wrapped balance would turn a very large credit into a debit.

There is deliberately **no `divide` method**. Splitting goes through `allocate(int)` and
`allocate(long... weights)`, which distribute the remainder one minor unit at a time and
are guaranteed to sum back to the original: €10.00 into three gives 3.34, 3.33, 3.33
rather than three lots of 3.33 with a cent discarded.

An ArchUnit rule fails the build if a `double`, `float`, `Double` or `Float` appears in a
domain field or return type.

In the database the same decision appears as a `BIGINT` column plus a `VARCHAR(3)`
currency. There is no decimal or floating-point column in the schema.

## Alternatives considered

**`BigDecimal`.** Exact, which is the main thing, and the usual answer in Java. Rejected
on three counts. Its scale is a property of each value rather than of the currency, so
`new BigDecimal("1.5")` and `new BigDecimal("1.50")` are not `equals` despite being the
same amount — a trap in any collection or assertion. It permits division without naming a
rounding mode, which throws at runtime instead of being prevented at compile time. And it
is a heap object with slower arithmetic, which matters when folding millions of postings
into a balance.

Where a decimal genuinely is wanted — display, or JSON at an adapter boundary —
`toDecimal()` produces an exact `BigDecimal` scaled by the currency. The conversion
happens at the edge, not in the model.

**A `long` with no currency**, relying on every account being single-currency. Simpler,
and almost workable since accounts are indeed single-currency. Rejected because it makes
mixing currencies a silent arithmetic error rather than a thrown exception, and the whole
point of a value type is that the compiler and the constructor catch what a convention
cannot.

**A scaled `long` with a fixed four decimal places**, as some payment systems use for FX.
Rejected for now as premature: no requirement here needs sub-minor-unit precision, and it
would make every value need an explicit scale conversion at the boundary. Worth revisiting
if FX rates are modelled, where fractional pips are real.

## Consequences

Every amount in the system is exact, and the build refuses to compile a domain type that
could hold an inexact one.

`long` overflows at roughly 92 quadrillion minor units — about €92 trillion — which is
comfortably beyond any balance this system will hold, and the overflow is loud rather than
silent if it ever is not.

Callers must think in minor units, including over HTTP: the API accepts and returns
`amountMinorUnits`, so no client can introduce a rounding error on the way in. The decimal
form is included in responses for display, but it is never the input.

The absence of `divide` occasionally makes a calculation more verbose than it would
otherwise be. That is the intended effect: splitting money is a decision about where the
remainder goes, and the API makes that decision explicit instead of letting it default.
