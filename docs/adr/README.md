# Architecture decision records

Short records of the decisions that shaped this system, including the alternatives that
were rejected and why. Records are never rewritten once accepted — superseding a decision
means adding a new record that references the old one.

| # | Decision | Status |
| --- | --- | --- |
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-synchronous-commands-asynchronous-facts.md) | Synchronous commands, asynchronous facts | Accepted |
| [0003](0003-orchestrated-saga-rather-than-choreography.md) | Orchestrated saga rather than choreography | Accepted |
| [0004](0004-transactional-outbox-for-event-publishing.md) | Transactional outbox for event publishing | Accepted |
| [0005](0005-customer-deposits-are-liabilities.md) | Customer deposits are liabilities | Accepted |
| [0006](0006-transactions-hold-n-legs-that-sum-to-zero.md) | Transactions hold N legs that sum to zero | Accepted |
| [0007](0007-idempotency-by-caller-supplied-reference.md) | Idempotency by caller-supplied reference | Accepted |
| [0008](0008-quarkus-confined-to-the-outer-layers.md) | Quarkus confined to the outer layers | Accepted |
| [0009](0009-plain-jakarta-persistence-rather-than-panache.md) | Plain Jakarta Persistence rather than Panache | Accepted |
| [0010](0010-money-as-integer-minor-units.md) | Money as integer minor units | Accepted |

## Planned

Decisions already taken but not yet written up, because the code they describe does not
exist yet:

- Reversal by contra entry rather than update or delete — when reversals are implemented
- Payment scheme routing, when `payments` gains more than one flow
- Whether the `accounts` balance read model is rebuilt from events or maintained
  incrementally
