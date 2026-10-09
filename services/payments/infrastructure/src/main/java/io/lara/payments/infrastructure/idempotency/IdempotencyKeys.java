package io.lara.payments.infrastructure.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * The idempotency key store, and the claim-then-fill protocol around it.
 *
 * <p>Lives in infrastructure rather than behind an application port, deliberately. What is stored
 * here is a serialised <em>HTTP response</em> — a status code and a JSON body — and that is an
 * edge concern through and through. Pushing it inward would mean the application layer knew what
 * a status code was.
 *
 * <h2>Claim, then fill</h2>
 *
 * <p>Two statements rather than one, because the work between them can take seconds.
 *
 * <ol>
 *   <li>{@link #claim} inserts the key with no response. One {@code on conflict do nothing}
 *       statement, so of two concurrent first attempts exactly one wins — decided by the primary
 *       key rather than by a read followed by a write, which has a window wide enough for both
 *       callers to conclude they were first.</li>
 *   <li>{@link #complete} fills in the response once the work is done.</li>
 * </ol>
 *
 * <p>Between the two the row exists with a null response, and that state is meaningful: it says a
 * request with this key is running right now. A duplicate arriving in that window is told so
 * rather than being given a half-finished answer.
 *
 * <p>If the work fails, {@link #release} removes the claim. A failed request must not be cached —
 * the client's retry is entitled to a real attempt, not a replay of an error.
 */
@ApplicationScoped
public class IdempotencyKeys {

    private final EntityManager entityManager;
    private final Duration retention;

    public IdempotencyKeys(
            EntityManager entityManager,
            @org.eclipse.microprofile.config.inject.ConfigProperty(
                    name = "payments.idempotency.retention", defaultValue = "PT24H") String retention) {

        this.entityManager = entityManager;
        this.retention = Duration.parse(retention);
    }

    /**
     * Takes the key, or reports who already has it.
     *
     * @return empty if this caller now owns the key; otherwise what is already stored against it
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<StoredResponse> claim(String key, String fingerprint, Instant now) {
        Instant created = now.truncatedTo(ChronoUnit.MICROS);

        int inserted = entityManager.createNativeQuery("""
                        insert into idempotency_key (key, request_fingerprint, created_at, expires_at)
                        values (:key, :fingerprint, :created, :expires)
                        on conflict (key) do nothing
                        """)
                .setParameter("key", key)
                .setParameter("fingerprint", fingerprint)
                .setParameter("created", created)
                .setParameter("expires", created.plus(retention))
                .executeUpdate();

        if (inserted == 1) {
            return Optional.empty();
        }

        // Somebody else holds it. Read back what they stored; the native insert bypassed the
        // persistence context, so clear it first or find() may answer from a stale cache.
        entityManager.clear();
        return Optional.of(read(key).orElseThrow(() -> new IllegalStateException(
                "idempotency key " + key + " conflicted on insert but then could not be read")));
    }

    /** Records the response this key's request produced. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void complete(String key, int status, String body) {
        entityManager.createNativeQuery("""
                        update idempotency_key
                        set response_status = :status, response_body = :body
                        where key = :key
                        """)
                .setParameter("status", status)
                .setParameter("body", body)
                .setParameter("key", key)
                .executeUpdate();
    }

    /**
     * Gives the key back after a failed attempt.
     *
     * <p>Caching a failure would turn one transient error into a permanent one for as long as the
     * retention window lasts, and the client would keep being handed the same 500 no matter how
     * healthy the system had become.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void release(String key) {
        entityManager.createNativeQuery("delete from idempotency_key where key = :key")
                .setParameter("key", key)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<StoredResponse> find(String key) {
        return read(key);
    }

    /**
     * Deletes keys past their expiry.
     *
     * @return how many went, so the scheduled job can report it and keep going while there is more
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int pruneExpired(Instant now, int batchSize) {
        return entityManager.createNativeQuery("""
                        delete from idempotency_key
                        where key in (
                            select key from idempotency_key
                            where expires_at < :now
                            order by expires_at
                            limit :batch
                        )
                        """)
                .setParameter("now", now)
                .setParameter("batch", batchSize)
                .executeUpdate();
    }

    private Optional<StoredResponse> read(String key) {
        return entityManager
                .createQuery("select k from IdempotencyKeyEntity k where k.key = :key",
                        IdempotencyKeyEntity.class)
                .setParameter("key", key)
                .getResultStream()
                .findFirst()
                .map(IdempotencyKeyEntity::toStored);
    }

    /**
     * A fingerprint of the request body.
     *
     * <p>Hashed rather than stored whole for two reasons: a request body can be large, and it can
     * contain details nobody needs a second copy of. A hash answers the only question being
     * asked, which is whether this is the same request as last time.
     */
    public static String fingerprintOf(String body) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every JVM. If it is missing, something is very wrong and
            // carrying on without a fingerprint would be worse than stopping.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * What is stored against a key.
     *
     * <p>An empty {@code status} means the request is still running. That is a real state rather
     * than missing data, and a duplicate arriving in that window has to be told about it.
     */
    public record StoredResponse(String fingerprint, Optional<Integer> status, Optional<String> body) {

        public boolean isStillRunning() {
            return status.isEmpty();
        }

        public boolean matches(String otherFingerprint) {
            return fingerprint.equals(otherFingerprint);
        }
    }
}
