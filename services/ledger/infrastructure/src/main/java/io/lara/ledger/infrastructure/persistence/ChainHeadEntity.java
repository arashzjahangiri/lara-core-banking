package io.lara.ledger.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.lara.ledger.domain.TransactionHash;

/**
 * The tail of the hash chain: exactly one row, holding the last hash and the last sequence number.
 *
 * <p>Locked {@code FOR UPDATE} while a transaction is appended. Without that, two concurrent
 * appends both read the same previous hash and the chain forks — two rows claiming the same
 * predecessor, and verification then fails on data nobody tampered with.
 *
 * <p>This serialises the tail of the write path, which is a real throughput ceiling and the price
 * of having a single verifiable sequence. A system needing more would shard the chain per account
 * and accept that there is no longer one global order.
 */
@Entity
@Table(name = "ledger_chain_head")
public class ChainHeadEntity {

    /** Always {@code true}. The primary key and a check constraint allow only one row. */
    public static final boolean SINGLETON_ID = true;

    @Id
    @Column(name = "id", nullable = false)
    private Boolean id;

    @Column(name = "last_hash", length = 64, nullable = false)
    private String lastHash;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    protected ChainHeadEntity() {
        // required by Hibernate
    }

    TransactionHash lastHash() {
        return new TransactionHash(lastHash);
    }

    long nextSequence() {
        return lastSequence + 1;
    }

    void advanceTo(TransactionHash hash, long sequence) {
        this.lastHash = hash.value();
        this.lastSequence = sequence;
    }
}
