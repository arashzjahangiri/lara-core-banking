package io.lara.risk.infrastructure.persistence;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import io.lara.risk.application.ScreeningDecisions;
import io.lara.risk.domain.RecordedScreening;
import io.lara.risk.domain.ScreeningReference;

/**
 * Writes decisions down and reads them back by reference.
 *
 * <p>The reference is the table's primary key, so two concurrent first attempts at the same
 * screening cannot both succeed — one of them violates the key. That is the race resolved by the
 * database rather than by a read-then-write in this class, which would have a window between the
 * two statements wide enough for both callers to decide they were first.
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

    @Override
    public void record(RecordedScreening screening) {
        // persist, not merge. A decision that has been returned to a caller is history, and merge
        // would quietly overwrite it — turning a duplicate reference into a silent rewrite of an
        // answer somebody has already acted on.
        entityManager.persist(ScreeningDecisionEntity.from(screening));
    }
}
