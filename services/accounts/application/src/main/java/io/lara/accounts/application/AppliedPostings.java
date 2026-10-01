package io.lara.accounts.application;

/**
 * Remembers which postings have already been applied.
 *
 * <p>Delivery is at-least-once: Debezium republishes after a restart, so the same event *will*
 * arrive twice. Without this the balance moves twice and the projection quietly diverges from the
 * ledger — the exact failure the whole outbox design exists to make impossible on the write side.
 *
 * <p>{@link #claim} both records and reports in one step, deliberately. A separate
 * "have I seen this?" followed by "mark it seen" is a race: two consumer threads can both read
 * "no" before either writes. One atomic claim, backed by a primary key, cannot.
 */
public interface AppliedPostings {

    /**
     * @return {@code true} if this call claimed the posting and the caller should apply it,
     *     {@code false} if it had already been applied and the caller must do nothing
     */
    boolean claim(String ledgerAccountId, String transactionId);
}
