package io.lara.accounts.application;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import io.lara.accounts.domain.CustomerBalance;
import io.lara.accounts.domain.Money;

/**
 * Applies a posting published by the ledger to this service's balance projection.
 *
 * <p>Three things have to be true of this, and they are the reason it is a use case rather than a
 * few lines inside the Kafka consumer:
 *
 * <ol>
 *   <li><strong>Idempotent.</strong> Delivery is at-least-once, so the same event will arrive
 *       twice. Applying it twice moves the balance twice.
 *   <li><strong>Atomic per account.</strong> A transaction that touches one account with two legs
 *       applies both or neither; a half-applied movement is a wrong balance.
 *   <li><strong>Honest about staleness.</strong> Every balance records when it was last updated,
 *       so a consumer that falls behind is visible rather than silently serving old numbers.
 * </ol>
 */
public final class ApplyLedgerPosting {

    private final CustomerBalances balances;
    private final AppliedPostings applied;
    private final Clock clock;

    public ApplyLedgerPosting(CustomerBalances balances, AppliedPostings applied, Clock clock) {
        this.balances = Objects.requireNonNull(balances, "balances must not be null");
        this.applied = Objects.requireNonNull(applied, "applied must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @return the balance after applying the event, or the unchanged balance when the event had
     *     already been applied
     */
    public Outcome apply(LedgerPostingEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        // Claim first. If this posting was already applied, nothing below must run — not even
        // reading the balance, so that a redelivery is as close to a no-op as it can be.
        if (!applied.claim(event.account(), event.transactionId())) {
            return new Outcome(balances.findByLedgerAccount(event.account()).orElse(null), false);
        }

        Money zero = Money.zero(event.currency());
        CustomerBalance balance = balances.findByLedgerAccount(event.account())
                .orElseGet(() -> CustomerBalance.opening(event.account(), zero, clock.instant()));

        CustomerBalance updated = balance;
        for (LedgerPostingEvent.Leg leg : event.legs()) {
            updated = updated.apply(
                    leg.side(),
                    Money.of(leg.amountMinorUnits(), leg.currency()),
                    event.transactionId(),
                    clock.instant());
        }

        balances.save(updated);
        return new Outcome(updated, true);
    }

    /**
     * What happened.
     *
     * @param balance the balance now held, which may be {@code null} only if a redelivery arrived
     *     for an account whose projection has since been removed
     * @param applied whether this call changed anything
     */
    public record Outcome(CustomerBalance balance, boolean applied) {

        public Optional<CustomerBalance> balanceIfPresent() {
            return Optional.ofNullable(balance);
        }
    }
}
