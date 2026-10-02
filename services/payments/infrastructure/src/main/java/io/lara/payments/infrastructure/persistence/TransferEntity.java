package io.lara.payments.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import io.lara.payments.domain.Iban;
import io.lara.payments.domain.LedgerTransactionRef;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.RejectionReason;
import io.lara.payments.domain.RoutingDecision;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferReference;
import io.lara.payments.domain.TransferScheme;
import io.lara.payments.domain.TransferState;
import io.lara.payments.domain.TransferStatus;
import io.lara.payments.domain.TransferTransition;

/**
 * A saga, as a row.
 *
 * <p>Separate from {@link Transfer} rather than annotating it, like every other entity here. The
 * aggregate refuses illegal transitions and has no no-arg constructor to offer Hibernate; giving
 * it one would mean a {@code Transfer} could exist half-populated and in no valid state at all.
 *
 * <h2>Flattening a sealed hierarchy</h2>
 *
 * <p>{@code TransferState} is a sealed interface whose cases carry different data. A row is flat,
 * so the status goes in one column and each case's evidence goes in its own nullable column. The
 * mapping back is an exhaustive switch on the status, and the database has check constraints
 * saying which columns must be present for which status — so a row hand-edited into an
 * impossible shape is rejected by Postgres rather than discovered here.
 */
@Entity
@Table(name = "transfer")
public class TransferEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /**
     * The optimistic lock.
     *
     * <p>Hibernate increments this on every update and adds {@code where version = ?} to the
     * statement. A write built from a stale read matches no rows and raises
     * {@code OptimisticLockException} instead of quietly winning.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "reference", nullable = false, length = 64)
    private String reference;

    @Column(name = "debtor_iban", nullable = false, length = 34)
    private String debtorIban;

    @Column(name = "creditor_iban", nullable = false, length = 34)
    private String creditorIban;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "scheme", nullable = false, length = 32)
    private String scheme;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;

    @Column(name = "reversal_transaction_id")
    private UUID reversalTransactionId;

    @Column(name = "rejection_reason", length = 32)
    private String rejectionReason;

    @Column(name = "state_detail", length = 500)
    private String stateDetail;

    /** When the saga last moved. The recovery sweep orders by this to find what has stalled. */
    @Column(name = "last_touched_at", nullable = false)
    private Instant lastTouchedAt;

    /**
     * The history.
     *
     * <p>Cascaded and orphan-removed so one {@code merge} writes the saga and its new transitions
     * together. Ordered by sequence rather than timestamp: two transitions can land in the same
     * microsecond and the order still has to be unambiguous.
     */
    @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = jakarta.persistence.FetchType.EAGER)
    @OrderBy("sequence ASC")
    private List<TransferTransitionEntity> transitions = new ArrayList<>();

    protected TransferEntity() {
        // Hibernate.
    }

    public static TransferEntity from(Transfer transfer, Instant touchedAt) {
        TransferEntity entity = new TransferEntity();
        entity.id = transfer.id().value();
        entity.reference = transfer.reference().value();
        entity.debtorIban = transfer.debtor().value();
        entity.creditorIban = transfer.creditor().value();
        entity.amountMinor = transfer.amount().minorUnits();
        entity.feeMinor = transfer.fee().minorUnits();
        entity.currency = transfer.amount().currency().getCurrencyCode();
        entity.scheme = transfer.scheme().name();
        entity.valueDate = transfer.valueDate();
        entity.requestedBy = transfer.requestedBy();
        entity.requestedAt = transfer.requestedAt();
        entity.applyState(transfer, touchedAt);
        return entity;
    }

    /** Copies the current state and any transitions not yet written. */
    public void applyState(Transfer transfer, Instant touchedAt) {
        this.status = transfer.status().name();
        this.lastTouchedAt = touchedAt;

        this.ledgerTransactionId = null;
        this.reversalTransactionId = null;
        this.rejectionReason = null;
        this.stateDetail = null;

        switch (transfer.state()) {
            case TransferState.Requested ignored -> { /* carries nothing */ }
            case TransferState.Screening ignored -> { /* carries nothing */ }
            case TransferState.Posting ignored -> { /* carries nothing */ }
            case TransferState.ApprovalPending pending -> this.stateDetail = pending.requestedBy();
            case TransferState.Posted posted ->
                    this.ledgerTransactionId = posted.ledgerTransaction().value();
            case TransferState.Completed completed ->
                    this.ledgerTransactionId = completed.ledgerTransaction().value();
            case TransferState.Rejected rejected -> {
                this.rejectionReason = rejected.reason().name();
                this.stateDetail = rejected.detail();
            }
            case TransferState.Compensating compensating -> {
                this.ledgerTransactionId = compensating.ledgerTransaction().value();
                this.stateDetail = compensating.cause();
            }
            case TransferState.Compensated compensated -> {
                this.ledgerTransactionId = compensated.ledgerTransaction().value();
                this.reversalTransactionId = compensated.reversal().value();
                this.stateDetail = compensated.cause();
            }
            case TransferState.Failed failed -> this.stateDetail = failed.cause();
        }

        // Append only what is new. Rewriting the whole list would delete and reinsert history
        // that has not changed, and orphanRemoval would make that a real delete.
        List<TransferTransition> history = transfer.history();
        for (int sequence = transitions.size(); sequence < history.size(); sequence++) {
            transitions.add(TransferTransitionEntity.of(this, sequence, history.get(sequence)));
        }
    }

    public Transfer toDomain(java.time.Clock clock) {
        Currency denomination = Currency.getInstance(currency);

        RoutingDecision routing = new RoutingDecision(
                TransferScheme.valueOf(scheme), Money.of(feeMinor, denomination), valueDate);

        return Transfer.rehydrate(
                TransferId.of(id),
                TransferReference.of(reference),
                Iban.of(debtorIban),
                Iban.of(creditorIban),
                Money.of(amountMinor, denomination),
                routing,
                requestedBy,
                requestedAt,
                readState(),
                transitions.stream().map(TransferTransitionEntity::toDomain).toList(),
                clock);
    }

    /**
     * Rebuilds the sealed state from the flat columns.
     *
     * <p>Exhaustive over {@link TransferStatus} with no default, so adding a state stops this
     * compiling until someone decides how it is stored. The {@code orElseThrow} calls are not
     * defensive padding — a row whose status claims money moved but has no transaction id is
     * corrupt, and the database's own check constraint should already have refused it. Failing
     * loudly here is how that shows up as a bug rather than a null downstream.
     */
    private TransferState readState() {
        TransferStatus stored = TransferStatus.valueOf(status);

        return switch (stored) {
            case REQUESTED -> new TransferState.Requested();
            case SCREENING -> new TransferState.Screening();
            case POSTING -> new TransferState.Posting();
            case APPROVAL_PENDING -> new TransferState.ApprovalPending(
                    required(stateDetail, "approval_pending without the maker"));
            case POSTED -> new TransferState.Posted(ledgerRef());
            case COMPLETED -> new TransferState.Completed(ledgerRef());
            case REJECTED -> new TransferState.Rejected(
                    RejectionReason.valueOf(required(rejectionReason, "rejected without a reason")),
                    required(stateDetail, "rejected without a detail"));
            case COMPENSATING -> new TransferState.Compensating(
                    ledgerRef(), required(stateDetail, "compensating without a cause"));
            case COMPENSATED -> new TransferState.Compensated(
                    ledgerRef(),
                    LedgerTransactionRef.of(required(
                            reversalTransactionId, "compensated without a reversal")),
                    required(stateDetail, "compensated without a cause"));
            case FAILED -> new TransferState.Failed(required(stateDetail, "failed without a cause"));
        };
    }

    private LedgerTransactionRef ledgerRef() {
        return LedgerTransactionRef.of(required(
                ledgerTransactionId, status + " without a ledger transaction"));
    }

    private static <T> T required(T value, String whatIsWrong) {
        if (value == null) {
            throw new IllegalStateException("transfer row is corrupt: " + whatIsWrong);
        }
        return value;
    }

    public long version() {
        return version;
    }
}
