-- Idempotency at the HTTP edge.
--
-- ADR-0007 already made the ledger idempotent by a caller-supplied reference, and that protects
-- the posting. It does not protect the saga: a client whose connection drops during POST
-- /transfers has no idea whether a transfer was created, and retrying would start a second one
-- against the same money.
--
-- So the edge needs its own key, and the guarantee is stronger than "do not duplicate". A replay
-- must return the ORIGINAL response, because answering a retry with a freshly created transfer
-- leaves the client holding two ids for one payment.

CREATE TABLE idempotency_key (
    -- The client's key. The primary key, which is what makes two concurrent first attempts
    -- resolve in the database rather than in a check-then-act race in the service.
    key                 VARCHAR(255)             PRIMARY KEY,

    -- SHA-256 of the request body, hex encoded. Reusing a key for a different request is a
    -- client bug and has to be reported as one: silently returning the first transfer's
    -- response would tell the caller their second, different payment had succeeded.
    request_fingerprint VARCHAR(64)              NOT NULL,

    -- Null until the request finishes. A row with a key and no response is a request still in
    -- flight, which is exactly what a concurrent duplicate needs to be told.
    response_status     INTEGER,
    response_body       TEXT,

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Keys do not live forever. Without an expiry this table grows without bound, and a key
    -- from a year ago is of no use to a client that has long since given up.
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    -- A response is all or nothing: a status without a body, or a body without a status, is a
    -- half-written row that would be replayed as a malformed answer.
    CONSTRAINT idempotency_response_complete
        CHECK ((response_status IS NULL) = (response_body IS NULL)),

    CONSTRAINT idempotency_expires_after_creation CHECK (expires_at > created_at)
);

-- The pruning job reads oldest-first and deletes in batches.
CREATE INDEX idempotency_key_expires_idx ON idempotency_key (expires_at);

COMMENT ON TABLE idempotency_key IS
    'One row per client idempotency key: the request it belongs to and the response it was given.';

COMMENT ON COLUMN idempotency_key.response_status IS
    'Null while the request is still running. A duplicate arriving then gets 409, not a replay.';
