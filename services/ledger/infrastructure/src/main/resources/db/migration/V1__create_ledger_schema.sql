-- The ledger schema.
--
-- Two rules are enforced by the database rather than by the application, because a rule that
-- lives only in code is one deployment away from not existing:
--
--   1. Money is BIGINT minor units plus an explicit currency. There is no floating-point column
--      anywhere, so no value can drift.
--   2. Transactions and their legs are append-only. UPDATE and DELETE are refused by a trigger,
--      not merely avoided by convention.

CREATE TABLE ledger_account (
    id            VARCHAR(64)              PRIMARY KEY,
    account_class VARCHAR(16)              NOT NULL,
    currency      CHAR(3)                  NOT NULL,
    opened_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT ledger_account_id_format
        CHECK (id ~ '^[A-Z0-9]+(\.[A-Z0-9]+)*$'),
    CONSTRAINT ledger_account_class_known
        CHECK (account_class IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'))
);

COMMENT ON TABLE ledger_account IS
    'The chart of accounts. An account holds no balance; balances are derived from postings.';
COMMENT ON COLUMN ledger_account.account_class IS
    'Determines the normal side. A customer deposit is a LIABILITY: the bank owes it.';

CREATE TABLE ledger_transaction (
    id          UUID                     PRIMARY KEY,
    reference   VARCHAR(128)             NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    -- The idempotency key. Two concurrent posts of one reference both see "not found" and both
    -- try to insert; this constraint is what makes the second one lose, so the adapter can catch
    -- the violation and answer with the original instead of posting twice.
    CONSTRAINT ledger_transaction_reference_unique UNIQUE (reference)
);

COMMENT ON COLUMN ledger_transaction.reference IS
    'The caller''s idempotency key. Distinct from id, which is the ledger''s own identity.';
COMMENT ON COLUMN ledger_transaction.occurred_at IS
    'When the movement happened. recorded_at is when this row was written; they differ on replay.';

CREATE TABLE posting_leg (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID        NOT NULL,
    leg_index      SMALLINT    NOT NULL,
    account_id     VARCHAR(64) NOT NULL,
    side           VARCHAR(6)  NOT NULL,
    amount_minor   BIGINT      NOT NULL,
    currency       CHAR(3)     NOT NULL,

    CONSTRAINT posting_leg_transaction_fk
        FOREIGN KEY (transaction_id) REFERENCES ledger_transaction (id),
    CONSTRAINT posting_leg_account_fk
        FOREIGN KEY (account_id) REFERENCES ledger_account (id),
    CONSTRAINT posting_leg_side_known
        CHECK (side IN ('DEBIT', 'CREDIT')),
    -- Direction is carried by side, never by the sign, so a leg amount is always positive.
    CONSTRAINT posting_leg_amount_positive
        CHECK (amount_minor > 0),
    CONSTRAINT posting_leg_order_unique
        UNIQUE (transaction_id, leg_index)
);

COMMENT ON TABLE posting_leg IS
    'One side of one movement. A transaction has two or more legs whose debits equal its credits.';

-- The balance query reads every leg for one account, ordered by when its transaction occurred.
CREATE INDEX posting_leg_account_idx ON posting_leg (account_id);
CREATE INDEX posting_leg_transaction_idx ON posting_leg (transaction_id);
CREATE INDEX ledger_transaction_occurred_at_idx ON ledger_transaction (occurred_at);

-- Append-only, enforced physically.
--
-- A ledger corrects itself by posting a contra entry, never by editing or deleting what was
-- written. Without this, a single careless UPDATE in a console session rewrites history and the
-- audit trail silently becomes fiction.
CREATE OR REPLACE FUNCTION ledger_refuse_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        '% is append-only; % is not permitted. Post a contra entry instead.',
        TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_transaction_append_only
    BEFORE UPDATE OR DELETE ON ledger_transaction
    FOR EACH ROW EXECUTE FUNCTION ledger_refuse_mutation();

CREATE TRIGGER posting_leg_append_only
    BEFORE UPDATE OR DELETE ON posting_leg
    FOR EACH ROW EXECUTE FUNCTION ledger_refuse_mutation();
