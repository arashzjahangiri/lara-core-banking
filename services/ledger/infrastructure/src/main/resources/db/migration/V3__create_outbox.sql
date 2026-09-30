-- The transactional outbox.
--
-- The ledger must record a posting *and* tell the rest of the platform about it. Those are two
-- systems, so they cannot share a transaction, and there is no safe ordering: write-then-publish
-- loses the event on a crash, publish-then-write announces a posting that never happened.
--
-- So the ledger never writes to Kafka. It inserts a row here in the SAME transaction as the
-- postings — either both commit or neither does — and Debezium publishes what committed by
-- reading the write-ahead log.
--
-- One row per (transaction, account). The aggregate id is the account, so Kafka partitions by
-- account and every movement affecting one account stays ordered relative to the others. A single
-- event per transaction keyed by transaction id would spread one account's history across
-- partitions and the balance read model could apply it out of order.

CREATE TABLE outbox (
    id             UUID                     PRIMARY KEY,
    aggregate_type VARCHAR(64)              NOT NULL,
    aggregate_id   VARCHAR(64)              NOT NULL,
    event_type     VARCHAR(64)              NOT NULL,
    payload        JSONB                    NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

COMMENT ON TABLE outbox IS
    'Transport buffer, not an audit record. Rows are pruned after publication; postings never are.';
COMMENT ON COLUMN outbox.aggregate_id IS
    'The account. Becomes the Kafka message key, so one account''s events stay ordered.';

-- Pruning reads oldest-first.
CREATE INDEX outbox_created_at_idx ON outbox (created_at);

-- Deliberately NOT append-only.
--
-- Unlike ledger_transaction and posting_leg, this table is a queue. Rows are deleted once Debezium
-- has published them, so the append-only trigger must not be attached here. The distinction is the
-- point: the ledger is history and cannot be rewritten, the outbox is plumbing and must be emptied.
