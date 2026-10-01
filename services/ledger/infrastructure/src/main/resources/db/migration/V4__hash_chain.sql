-- Tamper-evident hash chain.
--
-- Each transaction stores its own hash and the hash of the one before it. Altering any posting
-- changes its hash, which changes every hash after it, so a single edit cannot be hidden by
-- fixing one row.
--
-- This detects tampering; it does not prevent it. Anyone able to rewrite rows can recompute the
-- chain forward. Preventing that needs the hashes published somewhere the operator does not
-- control, which is out of scope and said plainly in ADR-0012.

ALTER TABLE ledger_transaction
    ADD COLUMN chain_sequence BIGINT,
    ADD COLUMN content_hash   VARCHAR(64),
    ADD COLUMN previous_hash  VARCHAR(64);

-- A strict, gap-free order. The chain is a sequence, and "ordered by timestamp" is not one:
-- two transactions can share an instant, and clocks move backwards.
CREATE SEQUENCE ledger_chain_sequence OWNED BY ledger_transaction.chain_sequence;

-- Existing rows predate the chain. There are none in any deployed database — this schema has
-- never been released — but assigning them explicitly keeps the migration correct if that ever
-- stops being true, and the verifier starts from the first row that has a hash.
UPDATE ledger_transaction SET chain_sequence = nextval('ledger_chain_sequence') WHERE chain_sequence IS NULL;

ALTER TABLE ledger_transaction
    ALTER COLUMN chain_sequence SET NOT NULL,
    ADD CONSTRAINT ledger_transaction_chain_sequence_unique UNIQUE (chain_sequence);

CREATE INDEX ledger_transaction_chain_idx ON ledger_transaction (chain_sequence);

COMMENT ON COLUMN ledger_transaction.content_hash IS
    'SHA-256 over this transaction plus previous_hash. Breaks visibly if any posting is altered.';

-- The chain head.
--
-- One row, locked FOR UPDATE while a transaction is appended. Without it two concurrent appends
-- both read the same previous hash and the chain forks — both rows claim the same predecessor and
-- verification fails on honest data. The lock serialises the tail of the write path, which is a
-- real throughput cost and the price of a single verifiable sequence.
CREATE TABLE ledger_chain_head (
    id             BOOLEAN     PRIMARY KEY DEFAULT TRUE,
    last_hash      VARCHAR(64)    NOT NULL,
    last_sequence  BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT ledger_chain_head_single_row CHECK (id)
);

COMMENT ON TABLE ledger_chain_head IS
    'Exactly one row, enforced by the primary key and check. Locked while appending to serialise the chain.';

-- Genesis: sixty-four zeros, so the chain is reproducible from the data alone rather than
-- depending on a stored secret.
INSERT INTO ledger_chain_head (id, last_hash, last_sequence)
VALUES (TRUE, repeat('0', 64), 0);
