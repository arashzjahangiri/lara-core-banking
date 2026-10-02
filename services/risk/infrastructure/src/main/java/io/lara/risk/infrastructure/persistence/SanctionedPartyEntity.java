package io.lara.risk.infrastructure.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.lara.risk.domain.SanctionedParty;

/**
 * One entry on a sanctions list, as a row.
 *
 * <p>The name is stored as it was published, not normalised. Normalisation is lossy — accents
 * and punctuation go — and the original is what a compliance officer needs to see when checking
 * whether a block was right. Matching happens on the normalised form that
 * {@code PartyName} derives, which is cheap enough to compute per screening and never has to be
 * kept in step with a stored copy.
 */
@Entity
@Table(name = "sanctioned_party")
public class SanctionedPartyEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "list_reference", nullable = false, length = 64)
    private String listReference;

    protected SanctionedPartyEntity() {
        // Hibernate.
    }

    public SanctionedParty toDomain() {
        return SanctionedParty.of(name, listReference);
    }
}