package io.lara.payments.application;

import java.time.Clock;
import java.util.Objects;

import io.lara.payments.domain.Iban;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.RoutingDecision;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferRouter;

/**
 * Accepts a transfer and writes it down. Nothing is attempted yet.
 *
 * <p>Routing happens here rather than later because the customer is quoted a fee and a value date
 * before they commit, and both have to be the ones that were quoted. Deciding them at posting
 * time would mean a transfer submitted at 15:59 and posted at 16:01 silently settled a day later
 * than the screen said it would.
 *
 * <p>The saga is persisted in {@code REQUESTED} and the caller returns immediately. Driving the
 * first step inside this call would tie the customer's HTTP request to the availability of three
 * other services — and a transfer that has been accepted should survive all of them being down.
 */
public final class RequestTransfer {

    private final Transfers transfers;
    private final TransferRouter router;
    private final Clock clock;

    public RequestTransfer(Transfers transfers, TransferRouter router, Clock clock) {
        this.transfers = Objects.requireNonNull(transfers, "transfers must not be null");
        this.router = Objects.requireNonNull(router, "router must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Records a new transfer in {@code REQUESTED}.
     *
     * @param requestedBy who asked; the maker, for four-eyes purposes
     */
    public Transfer request(Iban debtor, Iban creditor, Money amount, String requestedBy) {
        Objects.requireNonNull(debtor, "debtor must not be null");
        Objects.requireNonNull(creditor, "creditor must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        RoutingDecision routing = router.route(creditor, amount.currency(), clock.instant());

        Transfer transfer = Transfer.request(
                TransferId.newId(), debtor, creditor, amount, routing, requestedBy, clock);

        transfers.add(transfer);
        return transfer;
    }
}
