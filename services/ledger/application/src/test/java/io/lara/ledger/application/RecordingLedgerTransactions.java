package io.lara.ledger.application;

import java.util.ArrayList;
import java.util.List;

import io.lara.ledger.domain.LedgerTransaction;

/**
 * Records what was appended so a test can assert both that a posting happened and, just as
 * importantly, that a rejected posting wrote nothing.
 */
final class RecordingLedgerTransactions implements LedgerTransactions {

    private final List<LedgerTransaction> appended = new ArrayList<>();

    @Override
    public void append(LedgerTransaction transaction) {
        appended.add(transaction);
    }

    List<LedgerTransaction> appended() {
        return List.copyOf(appended);
    }

    boolean wroteNothing() {
        return appended.isEmpty();
    }
}
