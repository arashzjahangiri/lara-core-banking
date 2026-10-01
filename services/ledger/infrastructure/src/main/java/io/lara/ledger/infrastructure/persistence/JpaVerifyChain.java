package io.lara.ledger.infrastructure.persistence;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.lara.ledger.application.ChainIntegrity;
import io.lara.ledger.application.VerifyChain;
import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.TransactionHash;

/**
 * Walks the chain in sequence order, recomputing each hash and comparing it to what was recorded.
 *
 * <p>Reads in pages rather than loading the ledger into memory. A full ledger does not fit, and a
 * verifier that only works on a small one is not a verifier.
 */
@ApplicationScoped
public class JpaVerifyChain implements VerifyChain {

    private static final int PAGE_SIZE = 500;

    private final EntityManager entityManager;

    public JpaVerifyChain(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public ChainIntegrity verify() {
        TransactionHash previous = TransactionHash.GENESIS;
        long checked = 0;
        long after = 0;

        while (true) {
            List<LedgerTransactionEntity> page = entityManager
                    .createQuery("""
                            from LedgerTransactionEntity t
                            where t.chainSequence > :after
                            order by t.chainSequence
                            """, LedgerTransactionEntity.class)
                    .setParameter("after", after)
                    .setMaxResults(PAGE_SIZE)
                    .getResultList();

            if (page.isEmpty()) {
                return ChainIntegrity.intact(checked);
            }

            for (LedgerTransactionEntity entity : page) {
                checked++;
                after = entity.chainSequence();

                ChainIntegrity.Break failure = inspect(entity, previous);
                if (failure != null) {
                    // Stop at the first break. Everything after it fails too, because each hash
                    // depends on the one before, so listing them all would be noise.
                    return ChainIntegrity.broken(checked, failure);
                }
                previous = new TransactionHash(entity.contentHash());
            }
        }
    }

    private static ChainIntegrity.Break inspect(LedgerTransactionEntity entity, TransactionHash previous) {
        LedgerTransaction transaction = entity.toDomain();

        if (entity.contentHash() == null || entity.previousHash() == null) {
            return new ChainIntegrity.Break(entity.chainSequence(), transaction.id(),
                    ChainIntegrity.Reason.HASH_MISSING, previous.value(), null);
        }

        // Checked before the content hash, because a row inserted or removed in the middle shows
        // up here first and the reason is more useful than "content altered".
        if (!previous.value().equals(entity.previousHash())) {
            return new ChainIntegrity.Break(entity.chainSequence(), transaction.id(),
                    ChainIntegrity.Reason.CHAIN_BROKEN, previous.value(), entity.previousHash());
        }

        TransactionHash recomputed = TransactionHash.of(transaction, previous);
        if (!recomputed.value().equals(entity.contentHash())) {
            return new ChainIntegrity.Break(entity.chainSequence(), transaction.id(),
                    ChainIntegrity.Reason.CONTENT_ALTERED, recomputed.value(), entity.contentHash());
        }

        return null;
    }
}
