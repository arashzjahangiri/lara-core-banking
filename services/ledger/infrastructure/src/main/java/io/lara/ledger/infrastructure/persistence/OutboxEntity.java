package io.lara.ledger.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One pending event.
 *
 * <p>Written in the same database transaction as the postings it describes, which is what removes
 * the dual-write problem: there is no window in which a posting exists and its event does not, or
 * the other way round.
 *
 * <p>Nothing here publishes anything. Debezium reads the write-ahead log and sees only what
 * committed, so the application never holds a Kafka client on the write path.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", length = 64, nullable = false)
    private String aggregateType;

    /** The account. Becomes the Kafka message key, so one account's events stay ordered. */
    @Column(name = "aggregate_id", length = 64, nullable = false)
    private String aggregateId;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected OutboxEntity() {
        // required by Hibernate
    }

    static OutboxEntity event(String aggregateType, String aggregateId, String eventType, String payload) {
        OutboxEntity entity = new OutboxEntity();
        entity.id = UUID.randomUUID();
        entity.aggregateType = aggregateType;
        entity.aggregateId = aggregateId;
        entity.eventType = eventType;
        entity.payload = payload;
        return entity;
    }

    public UUID id() {
        return id;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public String payload() {
        return payload;
    }
}
