package io.lara.payments.infrastructure.idempotency;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One idempotency key, as a row.
 *
 * <p>Read-only from Hibernate's point of view: every write goes through a native statement in
 * {@link IdempotencyKeys}, because the insert needs {@code on conflict do nothing} and JPA has no
 * portable way to express that. This entity exists so the reads are typed and so Hibernate's
 * schema validation covers the table.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKeyEntity {

    @Id
    @Column(name = "key", nullable = false, length = 255)
    private String key;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyKeyEntity() {
        // Hibernate.
    }

    IdempotencyKeys.StoredResponse toStored() {
        return new IdempotencyKeys.StoredResponse(
                requestFingerprint,
                Optional.ofNullable(responseStatus),
                Optional.ofNullable(responseBody));
    }
}