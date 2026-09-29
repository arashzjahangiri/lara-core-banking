package io.lara.ledger.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.TransactionId;
import io.lara.ledger.domain.TransactionReference;

/**
 * Records what was appended so a test can assert both that a posting happened and, just as
 * importantly, that a rejected posting wrote nothing.
 *
 * <p>{@link #preempt(LedgerTransaction)} simulates losing the race the real adapter resolves with a
 * unique constraint: the next append finds someone else already recorded that reference.
 */
final class RecordingLedgerTransactions implements LedgerTransactions {

    private final List<LedgerTransaction> appended = new ArrayList<>();
    private LedgerTransaction preempted;

    /** The next append of this reference loses, as if another writer committed first. */
    void preempt(LedgerTransaction winner) {
        this.preempted = winner;
    }

    @Override
    public LedgerTransaction append(LedgerTransaction transaction) {
        if (preempted != null && preempted.reference().equals(transaction.reference())) {
            LedgerTransaction winner = preempted;
            preempted = null;
            appended.add(winner);
            return winner;
        }
        appended.add(transaction);
        return transaction;
    }

    @Override
    public Optional<LedgerTransaction> findByReference(TransactionReference reference) {
        return appended.stream().filter(t -> t.reference().equals(reference)).findFirst();
    }

    @Override
    public Optional<LedgerTransaction> findById(TransactionId id) {
        return appended.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    @Override
    public List<LedgerTransaction> findByAccountUpTo(AccountId account, Instant asOf) {
        return appended.stream()
                .filter(t -> !t.occurredAt().isAfter(asOf))
                .filter(t -> !t.legsFor(account).isEmpty())
                .toList();
    }

    List<LedgerTransaction> appended() {
        return List.copyOf(appended);
    }

    boolean wroteNothing() {
        return appended.isEmpty();
    }
}
