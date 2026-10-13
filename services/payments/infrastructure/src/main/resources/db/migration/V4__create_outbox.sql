-- The transfer lifecycle outbox.
--
-- ADR-0004 settled the mechanics in the ledger and the same reasoning applies here: an event
-- written in a different transaction from the state change is an event that can exist without
-- the fact it describes, or a fact nobody ever hears about. Writing both in one transaction
-- removes the window entirely.
--
-- Nothing in this service publishes anything. Debezium reads the write-ahead log and sees only
-- what committed, so a rolled-back saga step leaves no trace to publish.

CREATE TABLE outbox (
    id             UUID                     PRIMARY KEY,
    aggregate_type VARCHAR(64)              NOT NULL,

    -- The transfer id. Becomes the Kafka message key, so one transfer's events share a partition
    -- and arrive in order. A consumer that saw TransferCompensated before TransferCompleted would
    -- have to reason about a transfer that was reversed before it succeeded.
    aggregate_id   VARCHAR(64)              NOT NULL,

    event_type     VARCHAR(64)              NOT NULL,
    payload        JSONB                    NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

COMMENT ON TABLE outbox IS
    'A queue, not history. Rows are published by Debezium and then pruned.';

-- Pruning reads oldest-first once rows are beyond redelivery.
CREATE INDEX outbox_created_at_idx ON outbox (created_at);
