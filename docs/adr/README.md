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

## Planned

Decisions already taken but not yet written up, mostly because the code they describe
does not exist yet:

- Why clean architecture here, and what the duplication costs
- Why Quarkus is confined to `infrastructure` and `bootstrap`
- Why the application layer is annotation-free
- Why not Panache
- Why integer minor units rather than `BigDecimal`
- Reversal by contra entry rather than update or delete
