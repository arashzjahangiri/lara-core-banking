package io.lara.risk.infrastructure.persistence;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.lara.risk.application.ScreeningDecisions;
import io.lara.risk.domain.RecordedScreening;
import io.lara.risk.domain.ScreeningReference;

/**
 * Writes decisions down and reads them back by reference.
 *
 * <p>The reference is the table's primary key, which is what makes a retried screening find the
 * first answer instead of producing a second.
 */
@ApplicationScoped
public class JpaScreeningDecisions implements ScreeningDecisions {

    private final EntityManager entityManager;

    public JpaScreeningDecisions(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<RecordedScreening> findByReference(ScreeningReference reference) {
        return Optional.ofNullable(entityManager.find(ScreeningDecisionEntity.class, reference.value()))
                .map(ScreeningDecisionEntity::toDomain);
    }

    /**
     * Inserts the decision, or leaves the existing one alone, then returns whichever is stored.
     *
     * <p>Written as one {@code on conflict do nothing} statement rather than a check followed by
     * a {@code persist}. The check-then-write version has a window between its two statements
     * wide enough for two concurrent requests to both decide they are the first, and the loser
     * would then get a constraint violation after its transaction had already been marked for
     * rollback — turning a handled race into a 500.
     *
     * <p>{@code do nothing} rather than {@code do update} for the same reason the port insists
     * on it: a decision somebody has already acted on is history. The read-back afterwards is
     * what tells the loser what the winner decided, and it is always the authoritative row.
     *
     * <p>The same idiom as {@code applied_posting} in the accounts service, which resolves its
     * own at-least-once duplicates this way.
     */
    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public RecordedScreening record(RecordedScreening screening) {
        entityManager.createNativeQuery("""
                        insert into screening_decision
                            (reference, customer_id, amount_minor, currency,
                             beneficiary, outcome, reason, screened_at)
                        values
                            (:reference, cast(:customer as uuid), :amount, :currency,
                             :beneficiary, :outcome, :reason, :screenedAt)
                        on conflict (reference) do nothing
                        """)
                .setParameter("reference", screening.reference().value())
                .setParameter("customer", screening.customer().value().toString())
                .setParameter("amount", screening.amount().minorUnits())
                .setParameter("currency", screening.amount().currency().getCurrencyCode())
                .setParameter("beneficiary", screening.beneficiary().original())
                .setParameter("outcome", screening.outcome().name())
                .setParameter("reason", screening.decision().reason())
                .setParameter("screenedAt", screening.screenedAt())
                .executeUpdate();

        // Read back rather than trusting the insert's row count. If the conflict fired, the row
        // that is there is someone else's decision and that is the one the caller must be given.
        // The native insert bypasses the persistence context, so clear it first or find() can
        // answer from a stale first-level cache.
        entityManager.clear();

        return findByReference(screening.reference()).orElseThrow(() -> new IllegalStateException(
                "screening " + screening.reference() + " vanished immediately after being written"));
    }
}
