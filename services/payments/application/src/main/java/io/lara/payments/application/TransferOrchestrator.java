package io.lara.payments.application;

import java.util.Objects;
import java.util.Optional;

import io.lara.payments.domain.LedgerTransactionRef;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.RejectionReason;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferScheme;

/**
 * Drives a transfer through its states, one step at a time.
 *
 * <p>ADR-0003 chose orchestration so the workflow would live in one readable place. This is it:
 * every outbound call the saga makes is in this file, and the order they happen in is the order
 * they are written.
 *
 * <h2>One step per call</h2>
 *
 * <p>{@link #advanceOnce} performs exactly one step and returns. It does not loop. That is what
 * lets each step commit in its own transaction — the caller wraps one call, commits, and calls
 * again — and a step that spans two transactions is a step that can leave the saga in a state no
 * column records.
 *
 * <h2>Save before you call</h2>
 *
 * <p>Every transition is persisted before the next outbound call goes out, which is why entering
 * screening and acting on its answer are two separate steps rather than one. A crash during the
 * risk call then leaves the saga in {@code SCREENING} — a state that says exactly which call was
 * in flight — rather than in {@code REQUESTED}, which would say nothing had been attempted when
 * something had.
 *
 * <h2>What a failure may do</h2>
 *
 * <p>A step that fails never leaves the saga in its own state hoping to be retried into oblivion.
 * Before the money moves, failure means rejection. After it moves, the only exits are finishing
 * and compensating, and the state machine enforces that rather than this class remembering to.
 *
 * <p>An unreachable dependency is the exception: the saga stays where it is and the step reports
 * that it could not be taken, because an outage is not a decision and recovery will try again.
 */
public final class TransferOrchestrator {

    private final Transfers transfers;
    private final AccountDirectory accounts;
    private final RiskScreening risk;
    private final LedgerPosting ledger;
    private final SchemeGateway scheme;
    private final String sepaSuspenseAccount;
    private final String feeIncomeAccount;
    private final Money fourEyesThreshold;

    public TransferOrchestrator(
            Transfers transfers,
            AccountDirectory accounts,
            RiskScreening risk,
            LedgerPosting ledger,
            SchemeGateway scheme,
            String sepaSuspenseAccount,
            String feeIncomeAccount,
            Money fourEyesThreshold) {

        this.transfers = Objects.requireNonNull(transfers, "transfers must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.risk = Objects.requireNonNull(risk, "risk must not be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        this.sepaSuspenseAccount = Objects.requireNonNull(sepaSuspenseAccount, "suspense account must not be null");
        this.feeIncomeAccount = Objects.requireNonNull(feeIncomeAccount, "fee income account must not be null");
        this.fourEyesThreshold = Objects.requireNonNull(fourEyesThreshold, "threshold must not be null");
    }

    /**
     * Takes the single next step for this transfer, whatever that currently is.
     *
     * @return what happened, so the caller knows whether to come back for another step
     */
    public SagaProgress advanceOnce(TransferId id) {
        StoredTransfer stored = transfers.find(id).orElseThrow(
                () -> new IllegalArgumentException("no such transfer: " + id));

        return switch (stored.transfer().status()) {
            case REQUESTED -> validateAccounts(stored);
            case SCREENING -> screen(stored);
            case POSTING -> post(stored);
            case POSTED -> settle(stored);
            case COMPENSATING -> compensate(stored);

            // Waiting on a person. Nothing automatic moves this forward, and that is correct.
            case APPROVAL_PENDING -> SagaProgress.PARKED;

            case COMPLETED, REJECTED, COMPENSATED, FAILED -> SagaProgress.FINISHED;
        };
    }

    /**
     * Checks both accounts exist and can take part, then enters screening.
     *
     * <p>Done before risk is asked, because a transfer to an account that does not exist should
     * fail on its own merits rather than consuming a slot in the customer's daily screening
     * total. The creditor is only checked for an internal transfer — a SEPA beneficiary is at
     * another bank and this directory has never heard of them.
     */
    private SagaProgress validateAccounts(StoredTransfer stored) {
        Transfer transfer = stored.transfer();

        Optional<AccountSummary> debtor = accounts.find(transfer.debtor());
        if (debtor.isEmpty()) {
            return reject(stored, RejectionReason.UNKNOWN_ACCOUNT,
                    "no account " + transfer.debtor() + " at this bank");
        }
        if (!debtor.get().active()) {
            return reject(stored, RejectionReason.ACCOUNT_NOT_ACTIVE,
                    "account " + transfer.debtor() + " is not active");
        }
        if (!debtor.get().currency().equals(transfer.amount().currency())) {
            return reject(stored, RejectionReason.ACCOUNT_NOT_ACTIVE,
                    "account " + transfer.debtor() + " is held in "
                            + debtor.get().currency().getCurrencyCode() + ", not "
                            + transfer.amount().currency().getCurrencyCode());
        }

        if (transfer.scheme() == TransferScheme.INTERNAL) {
            Optional<AccountSummary> creditor = accounts.find(transfer.creditor());
            if (creditor.isEmpty()) {
                return reject(stored, RejectionReason.UNKNOWN_ACCOUNT,
                        "no account " + transfer.creditor() + " at this bank");
            }
            if (!creditor.get().active()) {
                return reject(stored, RejectionReason.ACCOUNT_NOT_ACTIVE,
                        "account " + transfer.creditor() + " is not active");
            }
        }

        transfer.startScreening();
        transfers.update(stored);
        return SagaProgress.ADVANCED;
    }

    /**
     * Asks risk, and acts on the answer.
     *
     * <p>A review verdict and a transfer over the four-eyes threshold both land in the same
     * place, which is deliberate: from the saga's point of view they are the same situation — a
     * person has to look at this before it proceeds.
     */
    private SagaProgress screen(StoredTransfer stored) {
        Transfer transfer = stored.transfer();

        AccountSummary debtor = accounts.find(transfer.debtor()).orElseThrow(
                () -> new IllegalStateException(
                        "account " + transfer.debtor() + " disappeared mid-saga"));

        ScreeningVerdict verdict = risk.screen(new ScreeningCommand(
                transfer.reference(),
                debtor.customerId(),
                transfer.totalDebit(),
                transfer.creditor().value()));

        return switch (verdict.outcome()) {
            case BLOCK -> reject(stored, RejectionReason.SCREENING_BLOCKED, verdict.reason());

            case REVIEW -> park(stored, "risk asked for a review: " + verdict.reason());

            // Allowed by risk, but still too large to go through on one person's say-so.
            case ALLOW -> transfer.amount().compareTo(fourEyesThreshold) > 0
                    ? park(stored, "above the four-eyes threshold of " + fourEyesThreshold)
                    : beginPosting(stored);
        };
    }

    /**
     * Asks the ledger to move the money.
     *
     * <p>The step where the outcome genuinely matters. A refusal is a decision and the transfer
     * is rejected; an unreachable ledger is not, and the saga stays in {@code POSTING} so that
     * recovery re-drives it. Re-driving is safe only because the posting reference is derived
     * from the transfer id and the ledger deduplicates on it.
     */
    private SagaProgress post(StoredTransfer stored) {
        Transfer transfer = stored.transfer();

        AccountSummary debtor = accounts.find(transfer.debtor()).orElseThrow(
                () -> new IllegalStateException(
                        "account " + transfer.debtor() + " disappeared mid-saga"));

        String creditorAccount = switch (transfer.scheme()) {
            case INTERNAL -> accounts.find(transfer.creditor())
                    .map(AccountSummary::ledgerAccountId)
                    .orElseThrow(() -> new IllegalStateException(
                            "account " + transfer.creditor() + " disappeared mid-saga"));

            // The beneficiary is at another bank, so the money sits in suspense until the
            // scheme settles it. Crediting nothing would leave the entry unbalanced.
            case SEPA_CREDIT_TRANSFER -> sepaSuspenseAccount;
        };

        LedgerTransactionRef posted;
        try {
            posted = ledger.post(new PostingCommand(
                    transfer.reference(),
                    debtor.ledgerAccountId(),
                    creditorAccount,
                    transfer.amount(),
                    transfer.fee(),
                    feeIncomeAccount,
                    transfer.valueDate()));
        } catch (LedgerRefusedException refused) {
            return reject(stored, RejectionReason.LEDGER_REFUSED, refused.getMessage());
        }

        transfer.posted(posted);
        transfers.update(stored);
        return SagaProgress.ADVANCED;
    }

    /**
     * Hands the payment on, or discovers that nobody will take it.
     *
     * <p>The only step that runs after the money has already moved, and therefore the only one
     * whose failure cannot be a rejection. An internal transfer skips it entirely — both accounts
     * are at this bank, so the ledger entry <em>is</em> the settlement and there is nobody to
     * hand anything to.
     *
     * <p>For a SEPA transfer the scheme can decline: a closed beneficiary account, a creditor
     * bank that will not accept it. That is the trigger compensation exists for. An unreachable
     * scheme is deliberately not: the submission may have landed, and reversing a payment the
     * scheme actually accepted would send the money back while the beneficiary is also being
     * paid.
     */
    private SagaProgress settle(StoredTransfer stored) {
        Transfer transfer = stored.transfer();

        if (transfer.scheme() == TransferScheme.INTERNAL) {
            return complete(stored);
        }

        SchemeAcknowledgement acknowledgement = scheme.submit(new SchemeSubmission(
                transfer.reference(),
                transfer.debtor(),
                transfer.creditor(),
                transfer.amount(),
                transfer.valueDate()));

        if (acknowledgement.accepted()) {
            return complete(stored);
        }

        // Money has moved and the scheme will not carry it. Rejection is not available here and
        // the state machine would refuse it anyway; the only honest exit is to put it back.
        transfer.startCompensation("scheme rejected the payment: " + acknowledgement.reason());
        transfers.update(stored);
        return SagaProgress.ADVANCED;
    }

    /** Nothing left to do. The money moved and everyone who had to accept it has. */
    private SagaProgress complete(StoredTransfer stored) {
        stored.transfer().complete();
        transfers.update(stored);
        return SagaProgress.FINISHED;
    }

    /**
     * Puts the money back.
     *
     * <p>Implemented here rather than in its own class because compensation is a saga step like
     * any other and the dispatcher above has to reach it. What it undoes, and why reversal is a
     * contra entry rather than a deletion, is ADR-0011's.
     */
    private SagaProgress compensate(StoredTransfer stored) {
        Transfer transfer = stored.transfer();

        // A COMPENSATING transfer always names the posting it is undoing — the state machine
        // will not let it reach this state otherwise — so the Optional is never empty here.
        LedgerTransactionRef original = transfer.ledgerTransaction().orElseThrow(
                () -> new IllegalStateException(
                        "transfer " + transfer.id() + " is compensating with nothing to reverse"));

        LedgerTransactionRef reversal = ledger.reverse(
                original, "compensating transfer " + transfer.id());

        transfer.compensated(reversal);
        transfers.update(stored);
        return SagaProgress.FINISHED;
    }

    private SagaProgress park(StoredTransfer stored, String why) {
        stored.transfer().awaitApproval(why);
        transfers.update(stored);
        return SagaProgress.PARKED;
    }

    private SagaProgress beginPosting(StoredTransfer stored) {
        stored.transfer().startPosting();
        transfers.update(stored);
        return SagaProgress.ADVANCED;
    }

    private SagaProgress reject(StoredTransfer stored, RejectionReason reason, String detail) {
        stored.transfer().reject(reason, detail);
        transfers.update(stored);
        return SagaProgress.FINISHED;
    }

}
