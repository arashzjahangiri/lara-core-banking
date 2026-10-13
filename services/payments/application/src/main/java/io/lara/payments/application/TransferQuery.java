package io.lara.payments.application;

import java.util.Objects;
import java.util.Optional;

import io.lara.payments.domain.Iban;
import io.lara.payments.domain.TransferStatus;

/**
 * What to look for, and how much of it.
 *
 * <h2>Why there is no customer filter</h2>
 *
 * <p>The obvious filter would be by customer, and this service cannot offer one. Payments does
 * not own customer identity — accounts does — and the only handle it holds is the debtor''s IBAN,
 * which identifies the paying account rather than the person behind it.
 *
 * <p>Inventing a customer filter would mean either storing a customer id payments has no
 * authority over, or calling accounts on every query to translate one. Both put a second copy of
 * someone else''s truth in this database. Filtering by account is the honest version of the same
 * question, and a caller that genuinely wants "everything this customer sent" asks accounts for
 * their IBANs first.
 */
public record TransferQuery(
        Optional<Iban> debtor,
        Optional<TransferStatus> status,
        Optional<String> requestedBy,
        int page,
        int size) {

    /** Past this, a single response is large enough to be a problem for somebody. */
    public static final int MAX_PAGE_SIZE = 200;

    public static final int DEFAULT_PAGE_SIZE = 25;

    public TransferQuery {
        Objects.requireNonNull(debtor, "debtor must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");

        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative, was " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("page size must be at least 1, was " + size);
        }
        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "page size must be at most " + MAX_PAGE_SIZE + ", was " + size);
        }
    }

    /** Everything, first page. */
    public static TransferQuery firstPage() {
        return new TransferQuery(
                Optional.empty(), Optional.empty(), Optional.empty(), 0, DEFAULT_PAGE_SIZE);
    }

    public TransferQuery withDebtor(Iban debtor) {
        return new TransferQuery(Optional.of(debtor), status, requestedBy, page, size);
    }

    public TransferQuery withStatus(TransferStatus status) {
        return new TransferQuery(debtor, Optional.of(status), requestedBy, page, size);
    }

    public TransferQuery withRequestedBy(String requestedBy) {
        return new TransferQuery(debtor, status, Optional.of(requestedBy), page, size);
    }

    public TransferQuery onPage(int page, int size) {
        return new TransferQuery(debtor, status, requestedBy, page, size);
    }

    public int offset() {
        return page * size;
    }
}