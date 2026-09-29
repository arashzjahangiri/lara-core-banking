package io.lara.ledger.infrastructure.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.PostingLeg;
import io.lara.ledger.domain.TransactionId;
import io.lara.ledger.domain.TransactionReference;

/**
 * The persistence shape of a transaction and its legs.
 *
 * <p>Legs cascade from here, so a transaction and everything it moved are written in one unit. A
 * half-written transaction would not balance, and a ledger that does not balance is worse than one
 * that rejected the write.
 */
@Entity
@Table(name = "ledger_transaction")
public class LedgerTransactionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "reference", length = 128, nullable = false, updatable = false)
    private String reference;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, insertable = false, updatable = false)
    private Instant recordedAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    private List<PostingLegEntity> legs = new ArrayList<>();

    protected LedgerTransactionEntity() {
        // required by Hibernate
    }

    public static LedgerTransactionEntity from(LedgerTransaction transaction) {
        LedgerTransactionEntity entity = new LedgerTransactionEntity();
        entity.id = transaction.id().value();
        entity.reference = transaction.reference().value();
        entity.occurredAt = transaction.occurredAt();

        List<PostingLeg> source = transaction.legs();
        for (int index = 0; index < source.size(); index++) {
            entity.legs.add(PostingLegEntity.from(entity, index, source.get(index)));
        }
        return entity;
    }

    public LedgerTransaction toDomain() {
        List<PostingLeg> ordered = legs.stream()
                .sorted(Comparator.comparingInt(PostingLegEntity::legIndex))
                .map(PostingLegEntity::toDomain)
                .toList();

        return new LedgerTransaction(
                TransactionId.of(id),
                TransactionReference.of(reference),
                occurredAt,
                ordered);
    }

    public Instant recordedAt() {
        return recordedAt;
    }
}
