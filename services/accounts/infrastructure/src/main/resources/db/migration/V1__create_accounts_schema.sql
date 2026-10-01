-- The accounts schema.
--
-- A separate database from the ledger, not a separate schema in the same one. Sharing storage
-- would let either service read the other's tables, and the moment that happens they can no
-- longer be deployed, migrated or scaled apart — a distributed monolith with extra deployments.

CREATE TABLE customer (
    id         UUID                     PRIMARY KEY,
    name       VARCHAR(200)             NOT NULL,
    kyc_status VARCHAR(16)              NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT customer_kyc_known CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED'))
);

CREATE TABLE account (
    iban              VARCHAR(34)              PRIMARY KEY,
    customer_id       UUID                     NOT NULL,
    ledger_account_id VARCHAR(64)              NOT NULL,
    currency          VARCHAR(3)               NOT NULL,
    status            VARCHAR(16)              NOT NULL,
    opened_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT account_customer_fk FOREIGN KEY (customer_id) REFERENCES customer (id),
    -- One account per ledger account. Two accounts pointing at the same ledger id would both
    -- claim the same money.
    CONSTRAINT account_ledger_unique UNIQUE (ledger_account_id),
    CONSTRAINT account_status_known
        CHECK (status IN ('OPENING', 'OPEN', 'FROZEN', 'CLOSED'))
);

CREATE INDEX account_customer_idx ON account (customer_id);

-- The balance projection.
--
-- A read model, not the truth. The ledger owns money and this is derived from its events, so the
-- two can disagree briefly and the ledger is right when they do. last_updated exists so a
-- consumer that falls behind is visible rather than silently serving stale numbers.
CREATE TABLE customer_balance (
    ledger_account_id   VARCHAR(64)              PRIMARY KEY,
    amount_minor        BIGINT                   NOT NULL,
    currency            VARCHAR(3)               NOT NULL,
    last_transaction_id VARCHAR(64),
    last_updated        TIMESTAMP WITH TIME ZONE NOT NULL
);

-- What has already been applied.
--
-- Delivery from Kafka is at-least-once: the same event *will* arrive twice after a connector
-- restart. The primary key is what makes the second arrival lose — an insert that violates it is
-- the signal to skip, and because it is one atomic statement two consumer threads cannot both
-- decide the posting is new.
CREATE TABLE applied_posting (
    ledger_account_id VARCHAR(64)              NOT NULL,
    transaction_id    VARCHAR(64)              NOT NULL,
    applied_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT applied_posting_pk PRIMARY KEY (ledger_account_id, transaction_id)
);

COMMENT ON TABLE applied_posting IS
    'Deduplication for at-least-once delivery. One row per account per transaction applied.';

-- Pruning reads oldest-first, once events are far enough in the past to be beyond redelivery.
CREATE INDEX applied_posting_applied_at_idx ON applied_posting (applied_at);
