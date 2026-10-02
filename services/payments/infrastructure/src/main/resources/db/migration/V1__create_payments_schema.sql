-- The payments schema.
--
-- Its own database. ADR-0003 is explicit that this service holds workflow state and never money,
-- and keeping the two in separate databases is what makes that structural rather than a
-- convention: a bug here cannot corrupt the record of what actually moved.

-- One row per transfer: the saga's state, and everything the routing decision fixed when the
-- customer committed to it.
CREATE TABLE transfer (
    id             UUID                     PRIMARY KEY,

    -- Optimistic locking. Two things touch a saga at once more often than it looks — the
    -- orchestrator advancing a step and the recovery sweep deciding the same saga is stuck —
    -- and without this the slower write silently overwrites the faster one, putting a completed
    -- transfer back in flight.
    version        BIGINT                   NOT NULL,

    -- The reference the ledger deduplicates on. Unique here too: two sagas quoting one reference
    -- would be two transfers that the ledger collapses into one posting, and only one of them
    -- would be telling the customer the truth.
    reference      VARCHAR(64)              NOT NULL,

    debtor_iban    VARCHAR(34)              NOT NULL,
    creditor_iban  VARCHAR(34)              NOT NULL,

    -- Integer minor units, per ADR-0010. The fee is separate from the amount because the
    -- creditor receives the amount and the debtor pays the amount plus the fee.
    amount_minor   BIGINT                   NOT NULL,
    fee_minor      BIGINT                   NOT NULL,
    currency       VARCHAR(3)               NOT NULL,

    scheme         VARCHAR(32)              NOT NULL,
    value_date     DATE                     NOT NULL,

    requested_by   VARCHAR(100)             NOT NULL,
    requested_at   TIMESTAMP WITH TIME ZONE NOT NULL,

    -- The sealed state hierarchy, flattened. The status names which case it is; the columns
    -- below hold whatever that case carries, and are null for the cases that carry nothing.
    status         VARCHAR(20)              NOT NULL,

    -- Evidence that money moved. Required by the check below for exactly the states that claim it.
    ledger_transaction_id  UUID,
    reversal_transaction_id UUID,
    rejection_reason       VARCHAR(32),

    -- Free text explaining this particular state: which limit was hit, which dependency timed
    -- out. For a human reading the row; code branches on status and rejection_reason.
    state_detail   VARCHAR(500),

    last_touched_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT transfer_reference_unique UNIQUE (reference),

    CONSTRAINT transfer_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT transfer_fee_not_negative CHECK (fee_minor >= 0),
    CONSTRAINT transfer_parties_differ CHECK (debtor_iban <> creditor_iban),

    CONSTRAINT transfer_status_known CHECK (status IN (
        'REQUESTED', 'SCREENING', 'APPROVAL_PENDING', 'POSTING', 'POSTED',
        'COMPLETED', 'REJECTED', 'COMPENSATING', 'COMPENSATED', 'FAILED')),

    CONSTRAINT transfer_scheme_known
        CHECK (scheme IN ('INTERNAL', 'SEPA_CREDIT_TRANSFER')),

    -- An internal book transfer involves no outside party, so there is nobody to charge.
    CONSTRAINT transfer_internal_is_free
        CHECK (scheme <> 'INTERNAL' OR fee_minor = 0),

    -- The invariant the state machine enforces in Java, restated where a hand-written UPDATE
    -- also has to obey it: every state that claims money moved must name the posting that
    -- moved it, and no state that claims otherwise may name one.
    CONSTRAINT transfer_posting_recorded_exactly_when_claimed CHECK (
        (status IN ('POSTED', 'COMPLETED', 'COMPENSATING', 'COMPENSATED')
            AND ledger_transaction_id IS NOT NULL)
        OR
        (status IN ('REQUESTED', 'SCREENING', 'APPROVAL_PENDING', 'POSTING', 'REJECTED', 'FAILED')
            AND ledger_transaction_id IS NULL)
    ),

    -- A reversal exists only once it has been posted.
    CONSTRAINT transfer_reversal_only_when_compensated
        CHECK ((status = 'COMPENSATED') = (reversal_transaction_id IS NOT NULL)),

    -- A rejection always has a reason, and nothing else has one.
    CONSTRAINT transfer_reason_only_when_rejected
        CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL))
);

-- The recovery sweep's query: non-terminal sagas that have not moved for a while, oldest first.
-- Partial, because the number of finished transfers only grows and the sweep never wants them.
CREATE INDEX transfer_stuck_idx
    ON transfer (last_touched_at)
    WHERE status NOT IN ('COMPLETED', 'REJECTED', 'COMPENSATED', 'FAILED');

COMMENT ON COLUMN transfer.version IS
    'Optimistic lock. Incremented by Hibernate on every update; a stale value loses the write.';

-- How each transfer got where it is.
--
-- A separate table rather than a JSON column, so the path can be queried — "how many transfers
-- went through APPROVAL_PENDING last week" is a question someone will ask, and it should not
-- require parsing a document to answer.
CREATE TABLE transfer_transition (
    transfer_id UUID                     NOT NULL,
    sequence    INTEGER                  NOT NULL,
    from_status VARCHAR(20)              NOT NULL,
    to_status   VARCHAR(20)              NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    note        VARCHAR(500)             NOT NULL,

    CONSTRAINT transfer_transition_pk PRIMARY KEY (transfer_id, sequence),
    CONSTRAINT transfer_transition_fk
        FOREIGN KEY (transfer_id) REFERENCES transfer (id) ON DELETE CASCADE
);

COMMENT ON TABLE transfer_transition IS
    'Append-only history of one transfer''s moves. Ordered by sequence, not by timestamp: two '
    'transitions can share a microsecond and the order still has to be unambiguous.';
