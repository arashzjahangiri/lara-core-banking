# 8. Quarkus confined to the outer layers

**Status:** Accepted
**Date:** 2026-10-01

## Context

Quarkus is a pleasure to write against. Annotate an entity, inject a repository, add
`@Transactional`, and a working service appears in an afternoon. The cost is that the
business rules end up expressed in the framework's vocabulary, and from then on they can
only be run the way the framework runs them — which means a container starts before any
test can assert that a transfer balances.

For a system whose point is its accounting rules, that is the wrong trade.

## Decision

Each service is four Maven modules with dependencies pointing strictly inward, and
**the build enforces it**:

```
domain/          no dependencies at all
application/     domain only
infrastructure/  application + domain + Quarkus, unrestrained
bootstrap/       everything; the Quarkus application
```

A module cannot use a framework it does not depend on, so the four `pom.xml` files are
the primary control. ArchUnit is the second line, catching what a build file cannot
express — a `double` creeping into a domain record, or a dependency added to the wrong
pom.

The `application` layer is **annotation-free**. No `@ApplicationScoped`, no `@Inject`, no
`@Transactional`. Use cases take their dependencies through the constructor, so every one
can be built with `new` in a test. Those are Jakarta annotations rather than Quarkus ones,
so permitting them would be defensible — refusing them is what guarantees the layer stays
runnable without a container.

The two worlds meet in exactly one place: a CDI producer class in `bootstrap`.

## Alternatives considered

**Annotate the domain and be done.** Fastest, and correct for most applications. Rejected
here because the domain rules are the thing being demonstrated, and a domain that needs a
container to run is a domain nobody will read.

**Allow Jakarta annotations in `application`, ban only Quarkus ones.** Tempting, and the
line is defensible — Jakarta is a specification, not a vendor. Rejected because
`@ApplicationScoped` still means "the container constructs this", and the moment a use case
is container-constructed, its test needs the container.

**One module per service with packages instead of modules.** Packages are a convention;
Maven modules are a mechanism. A package boundary is crossed by typing an import, and
nothing fails.

## Consequences

The domain and application suites — 151 tests — run in under a second with no container,
no database and no mocking framework. That speed is not a nicety: it is what makes the
accounting invariants cheap enough to test exhaustively.

The cost is real duplication. A `LedgerTransaction` record in `domain` and a
`LedgerTransactionEntity` with `@Entity` in `infrastructure`, plus mapping between them.
Two shapes of the same idea, kept in step by hand.

That cost is worth paying here and would not be worth paying in a CRUD service on a
deadline. Knowing which situation you are in matters more than the pattern.

A second consequence is pleasant and was not the goal: because ports are resolved at build
time, a missing binding fails the **build**. The first time `bootstrap` was wired, the
build failed with *"Unsatisfied dependency for type LedgerAccounts"* rather than starting
and throwing a null at the first request.
