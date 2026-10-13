package io.lara.payments.application;

import java.util.List;
import java.util.Objects;

/**
 * One page of results, and how many there are in total.
 *
 * <p>The total is included rather than left out, because without it a client cannot tell a last
 * page from a page that happens to be short, and every paging UI needs to know which it is
 * looking at. It costs a second count query, which is the right trade for a list a human reads.
 */
public record TransferPage(List<StoredTransfer> transfers, int page, int size, long total) {

    public TransferPage {
        Objects.requireNonNull(transfers, "transfers must not be null");
        transfers = List.copyOf(transfers);
    }

    public boolean hasMore() {
        return (long) (page + 1) * size < total;
    }
}