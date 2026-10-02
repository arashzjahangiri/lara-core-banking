package io.lara.payments.infrastructure.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import io.lara.payments.domain.TransferStatus;
import io.lara.payments.domain.TransferTransition;

/**
 * One move a transfer made, as a row.
 *
 * <p>Keyed by the transfer and a sequence number rather than by timestamp. Two transitions can
 * land in the same microsecond — the orchestrator advances several steps faster than
 * {@code timestamptz} can distinguish — and a history whose order depends on a tie-break is a
 * history that reads differently on different days.
 *
 * <h2>Why the parent is part of the key</h2>
 *
 * <p>This is JPA's derived identity: the {@code @ManyToOne} is itself an {@code @Id}, so the
 * foreign key column and the first half of the primary key are the same column, mapped once.
 *
 * <p>The obvious alternative — a unidirectional {@code @OneToMany} with a {@code @JoinColumn} on
 * the parent, plus a plain {@code transferId} field here — maps {@code transfer_id} twice and
 * Hibernate generates an insert naming it twice. Making the field read-only does not help: then
 * nothing populates it and the insert fails for a missing parameter instead.
 *
 * <p>Dropping {@code transferId} from the key is worse still. Two transitions from different
 * transfers would share sequence 0 and collide in the persistence context, so one transfer's
 * history would silently surface in another's.
 */
@Entity
@Table(name = "transfer_transition")
@IdClass(TransferTransitionEntity.Key.class)
public class TransferTransitionEntity {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false)
    private TransferEntity transfer;

    @Id
    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "from_status", nullable = false, length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "note", nullable = false, length = 500)
    private String note;

    protected TransferTransitionEntity() {
        // Hibernate.
    }

    static TransferTransitionEntity of(TransferEntity parent, int sequence, TransferTransition transition) {
        TransferTransitionEntity entity = new TransferTransitionEntity();
        entity.transfer = parent;
        entity.sequence = sequence;
        entity.fromStatus = transition.from().name();
        entity.toStatus = transition.to().name();
        entity.occurredAt = transition.at();
        entity.note = transition.note();
        return entity;
    }

    TransferTransition toDomain() {
        return new TransferTransition(
                TransferStatus.valueOf(fromStatus),
                TransferStatus.valueOf(toStatus),
                occurredAt,
                note);
    }

    /**
     * The composite key.
     *
     * <p>{@code transfer} is a {@code UUID} here rather than a {@code TransferEntity}: for a
     * derived identity, the key class holds the <em>primary key type</em> of the association, and
     * its field name has to match the entity's. Public because Hibernate instantiates it
     * reflectively.
     */
    public static final class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID transfer;
        private int sequence;

        public Key() {
            // Hibernate.
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return sequence == key.sequence && Objects.equals(transfer, key.transfer);
        }

        @Override
        public int hashCode() {
            return Objects.hash(transfer, sequence);
        }
    }
}
