package io.lara.risk.infrastructure.persistence;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import io.lara.risk.application.SanctionsSource;
import io.lara.risk.domain.SanctionedParty;
import io.lara.risk.domain.SanctionsList;

/**
 * Loads the sanctions list from the database on every screening.
 *
 * <p>Not cached, which is a deliberate trade. A regulator can publish an addition at any hour,
 * and a list read once at startup is stale for as long as the pod lives — weeks, for a service
 * that rarely restarts. The list is small and the query is a single indexed scan, so the cost of
 * being right is a few milliseconds per screening.
 *
 * <p>If it ever grows past that, the fix is a cache with an explicit, short expiry and a metric
 * on its age — not a cache that silently never refreshes.
 */
@ApplicationScoped
public class JpaSanctionsSource implements SanctionsSource {

    private final EntityManager entityManager;

    public JpaSanctionsSource(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public SanctionsList current() {
        List<SanctionedParty> parties = entityManager
                .createQuery("select p from SanctionedPartyEntity p order by p.name", SanctionedPartyEntity.class)
                .getResultList()
                .stream()
                .map(SanctionedPartyEntity::toDomain)
                .toList();

        return new SanctionsList(parties);
    }
}