package io.lara.payments.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One transfer, and the only thing allowed to move it between states.
 *
 * <p>ADR-0003 chose an orchestrated saga over choreography so the workflow would be written down
 * in one place. This class is that place. Every legal move is a method, every illegal one throws,
 * and there is no setter anywhere — a caller cannot put a transfer into {@code COMPLETED} without
 * going through {@link #complete()}, which can only be reached from {@code POSTED}.
 *
 * <p>Mutable, unlike most types in this project. An aggregate that accumulates history and is
 * loaded, advanced one step and saved is the one place where returning a new instance per
 * transition would obscure rather than clarify — and the version column that guards it, added
 * with the persistence adapter, assumes a single row being updated rather than replaced.
 *
 * <h2>The shape of the machine</h2>
 *
 * <pre>
 *   REQUESTED ──▶ SCREENING ──┬──▶ POSTING ──┬──▶ POSTED ──┬──▶ COMPLETED
 *                             │              │             │
 *                             │              └──▶ REJECTED └──▶ COMPENSATING ──▶ COMPENSATED
 *                             │                                      │
 *                             ├──▶ APPROVAL_PENDING ──▶ POSTING      └──▶ FAILED
 *                             │           │
 *                             └──▶ REJECTED ──▶ REJECTED
 * </pre>
 *
 * <p>Two properties of that diagram carry most of the safety. First, {@code REJECTED} is
 * unreachable once the money has moved, so a caller can read it as "nothing happened" without
 * further checking. Second, {@code POSTED} has exactly two exits and neither of them is
 * {@code FAILED} — once the ledger holds the money, giving up is not an available move, and the
 * only way out is to finish or to reverse.
 */
public final class Transfer {

    private final TransferId id;
    private final TransferReference reference;
    private final Iban debtor;
    private final Iban creditor;
    private final Money amount;
    private final RoutingDecision routing;
    private final String requestedBy;
    private final Instant requestedAt;
    private final Clock clock;
    private final List<TransferTransition> history;

    private TransferState state;

    private Transfer(
            TransferId id,
            TransferReference reference,
            Iban debtor,
            Iban creditor,
            Money amount,
            RoutingDecision routing,
            String requestedBy,
            Instant requestedAt,
            TransferState state,
            List<TransferTransition> history,
            Clock clock) {

        this.id = Objects.requireNonNull(id, "transfer id must not be null");
        this.reference = Objects.requireNonNull(reference, "transfer reference must not be null");
        this.debtor = Objects.requireNonNull(debtor, "debtor must not be null");
        this.creditor = Objects.requireNonNull(creditor, "creditor must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.routing = Objects.requireNonNull(routing, "routing must not be null");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.history = new ArrayList<>(Objects.requireNonNull(history, "history must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");

        if (amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException("a transfer must move a positive amount, was " + amount);
        }
        if (debtor.equals(creditor)) {
            throw new IllegalArgumentException("a transfer must have different debtor and creditor: " + debtor);
        }
        // Money refuses to add across currencies, so a mismatch here would not surface until
        // totalDebit() was called — somewhere downstream, with nothing pointing back to the
        // routing decision that caused it.
        if (!routing.fee().currency().equals(amount.currency())) {
            throw new IllegalArgumentException(
                    "fee is in " + routing.fee().currency().getCurrencyCode()
                            + " but the transfer is in " + amount.currency().getCurrencyCode());
        }
    }

    /**
     * A new transfer, in {@code REQUESTED}.
     *
     * <p>The posting reference is derived from the id rather than accepted from the caller. It has
     * to be identical across every attempt for the ledger's idempotency to hold, and a value
     * passed in is a value that can vary.
     */
    public static Transfer request(
            TransferId id,
            Iban debtor,
            Iban creditor,
            Money amount,
            RoutingDecision routing,
            String requestedBy,
            Clock clock) {

        Objects.requireNonNull(clock, "clock must not be null");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        return new Transfer(
                id,
                TransferReference.forTransfer(id),
                debtor,
                creditor,
                amount,
                routing,
                requestedBy,
                now,
                new TransferState.Requested(),
                List.of(),
                clock);
    }

    /**
     * Rebuilds a transfer from storage without replaying its transitions.
     *
     * <p>Replaying would re-validate moves that were already accepted, so a rule tightened after
     * this row was written would make it unloadable — the stored state becomes unreadable exactly
     * when someone is trying to investigate it. What is already history is restored as found.
     */
    public static Transfer rehydrate(
            TransferId id,
            TransferReference reference,
            Iban debtor,
            Iban creditor,
            Money amount,
            RoutingDecision routing,
            String requestedBy,
            Instant requestedAt,
            TransferState state,
            List<TransferTransition> history,
            Clock clock) {

        return new Transfer(
                id, reference, debtor, creditor, amount, routing, requestedBy, requestedAt,
                state, history, clock);
    }

    // ---------------------------------------------------------------- transitions

    /** Hand the transfer to risk. */
    public void startScreening() {
        requireCurrentlyIn(TransferStatus.SCREENING, TransferStatus.REQUESTED);
        moveTo(new TransferState.Screening(), "");
    }

    /** Risk allowed it, and it is small enough to post without a second person. */
    public void startPosting() {
        requireCurrentlyIn(TransferStatus.POSTING, TransferStatus.SCREENING, TransferStatus.APPROVAL_PENDING);
        moveTo(new TransferState.Posting(), "");
    }

    /** Risk allowed it, but it is over the four-eyes threshold. */
    public void awaitApproval() {
        requireCurrentlyIn(TransferStatus.APPROVAL_PENDING, TransferStatus.SCREENING);
        moveTo(new TransferState.ApprovalPending(requestedBy), "over the four-eyes threshold");
    }

    /**
     * The ledger accepted the posting. Money has now moved.
     *
     * <p>The point of no return: from here the transfer can only finish or be reversed, and
     * {@link #reject} and {@link #fail} are both closed off.
     */
    public void posted(LedgerTransactionRef ledgerTransaction) {
        requireCurrentlyIn(TransferStatus.POSTED, TransferStatus.POSTING);
        moveTo(new TransferState.Posted(ledgerTransaction), "ledger transaction " + ledgerTransaction);
    }

    /** Everything after the posting succeeded too. */
    public void complete() {
        requireCurrentlyIn(TransferStatus.COMPLETED, TransferStatus.POSTED);
        moveTo(new TransferState.Completed(postedTransaction()), "");
    }

    /**
     * Refused, with no money moved.
     *
     * <p>Deliberately unreachable from {@code POSTED} and beyond. A caller seeing
     * {@code REJECTED} is entitled to assume nothing happened, and that assumption is only safe
     * because the state machine will not let this be called once the ledger holds the money.
     */
    public void reject(RejectionReason reason, String detail) {
        requireCurrentlyIn(
                TransferStatus.REJECTED,
                TransferStatus.REQUESTED, TransferStatus.SCREENING, TransferStatus.APPROVAL_PENDING,
                TransferStatus.POSTING);
        moveTo(new TransferState.Rejected(reason, detail), reason + ": " + detail);
    }

    /** Something failed after the money moved; start putting it back. */
    public void startCompensation(String cause) {
        requireCurrentlyIn(TransferStatus.COMPENSATING, TransferStatus.POSTED);
        moveTo(new TransferState.Compensating(postedTransaction(), cause), cause);
    }

    /** The reversing entry is posted. The ledger now holds both. */
    public void compensated(LedgerTransactionRef reversal) {
        requireCurrentlyIn(TransferStatus.COMPENSATED, TransferStatus.COMPENSATING);
        TransferState.Compensating compensating = (TransferState.Compensating) state;
        moveTo(
                new TransferState.Compensated(
                        compensating.ledgerTransaction(), reversal, compensating.cause()),
                "reversed by " + reversal);
    }

    /**
     * Give up and ask for a person.
     *
     * <p>Not available from {@code POSTED}: the money is in the ledger and the saga does not get
     * to walk away from it. It <em>is</em> available from {@code COMPENSATING}, because a failed
     * reversal is the one case where money really is stranded and nothing automatic will fix it.
     */
    public void fail(String cause) {
        requireCurrentlyIn(
                TransferStatus.FAILED,
                TransferStatus.REQUESTED, TransferStatus.SCREENING, TransferStatus.APPROVAL_PENDING,
                TransferStatus.POSTING, TransferStatus.COMPENSATING);
        moveTo(new TransferState.Failed(cause), cause);
    }

    // ---------------------------------------------------------------- queries

    public TransferId id() {
        return id;
    }

    public TransferReference reference() {
        return reference;
    }

    public Iban debtor() {
        return debtor;
    }

    public Iban creditor() {
        return creditor;
    }

    /** What the creditor receives. The debtor pays this plus the fee — see {@link #totalDebit()}. */
    public Money amount() {
        return amount;
    }

    public RoutingDecision routing() {
        return routing;
    }

    public TransferScheme scheme() {
        return routing.scheme();
    }

    public Money fee() {
        return routing.fee();
    }

    public java.time.LocalDate valueDate() {
        return routing.valueDate();
    }

    /**
     * What leaves the debtor's account: the amount plus the fee.
     *
     * <p>Kept distinct from {@link #amount()} because the two differ and conflating them is how a
     * fee gets charged to the wrong side. The creditor is credited the amount; the debtor is
     * debited this; the difference is the bank's fee income, and the ledger entry has three legs.
     */
    public Money totalDebit() {
        return amount.plus(routing.fee());
    }

    public String requestedBy() {
        return requestedBy;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public TransferState state() {
        return state;
    }

    public TransferStatus status() {
        return state.status();
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    /** The whole path, oldest first. Unmodifiable: history is not something a caller edits. */
    public List<TransferTransition> history() {
        return Collections.unmodifiableList(history);
    }

    /**
     * The ledger transaction that moved the money, if it has moved.
     *
     * <p>Empty rather than null, and empty is a real answer: before {@code POSTED} there is no
     * such transaction, and after a rejection there never will be.
     */
    public java.util.Optional<LedgerTransactionRef> ledgerTransaction() {
        return switch (state) {
            case TransferState.Posted posted -> java.util.Optional.of(posted.ledgerTransaction());
            case TransferState.Completed completed -> java.util.Optional.of(completed.ledgerTransaction());
            case TransferState.Compensating compensating ->
                    java.util.Optional.of(compensating.ledgerTransaction());
            case TransferState.Compensated compensated ->
                    java.util.Optional.of(compensated.ledgerTransaction());
            case TransferState.Requested ignored -> java.util.Optional.empty();
            case TransferState.Screening ignored -> java.util.Optional.empty();
            case TransferState.ApprovalPending ignored -> java.util.Optional.empty();
            case TransferState.Posting ignored -> java.util.Optional.empty();
            case TransferState.Rejected ignored -> java.util.Optional.empty();
            case TransferState.Failed ignored -> java.util.Optional.empty();
        };
    }

    // ---------------------------------------------------------------- internals

    private LedgerTransactionRef postedTransaction() {
        return ledgerTransaction().orElseThrow(() -> new IllegalStateException(
                "no ledger transaction recorded in state " + status()));
    }

    /**
     * The single gate every transition passes through.
     *
     * <p>One method rather than a check per transition, so the rule cannot be stated two
     * different ways in two places. It names both ends of the rejected move, which is what makes
     * the failure readable in a log without going back to the source.
     */
    private void requireCurrentlyIn(TransferStatus target, TransferStatus... allowed) {
        for (TransferStatus candidate : allowed) {
            if (status() == candidate) {
                return;
            }
        }
        throw new IllegalTransferTransitionException(status(), target);
    }

    private void moveTo(TransferState next, String note) {
        history.add(new TransferTransition(
                status(), next.status(), clock.instant().truncatedTo(ChronoUnit.MICROS), note));
        state = next;
    }

    @Override
    public String toString() {
        return "Transfer " + id + " " + amount + " " + debtor + " -> " + creditor + " [" + status() + "]";
    }
}
