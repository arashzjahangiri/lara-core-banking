-- The risk schema.
--
-- Its own database, like every other service here. This one owns the rules it is accountable for
-- applying, and a shared schema would let another service change a limit that risk would then be
-- blamed for having enforced.

-- Per-customer thresholds.
--
-- Data rather than code, deliberately. A limit compiled into the service needs a deployment to
-- change, and a risk team that has to file a release ticket to lower someone's limit will not
-- lower it in time. Most customers have no row and run on the configured default.
CREATE TABLE customer_limits (
    customer_id        UUID                     PRIMARY KEY,
    daily_limit_minor  BIGINT                   NOT NULL,
    review_above_minor BIGINT                   NOT NULL,
    currency           VARCHAR(3)               NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT customer_limits_non_negative
        CHECK (daily_limit_minor >= 0 AND review_above_minor >= 0),
    -- A review threshold above the daily limit can never fire: anything large enough to reach it
    -- is already blocked. Rejected here as well as in the domain, because a row inserted by hand
    -- never passes through the domain at all.
    CONSTRAINT customer_limits_review_below_daily
        CHECK (review_above_minor <= daily_limit_minor)
);

-- The sanctions list.
--
-- Also data, and for a sharper reason: a regulator can publish an addition at any hour, and a
-- list that needs a release to update is wrong for as long as the release takes.
CREATE TABLE sanctioned_party (
    id             UUID                     PRIMARY KEY,
    name           VARCHAR(200)             NOT NULL,
    list_reference VARCHAR(64)              NOT NULL,
    added_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    -- The same name can appear on two lists, and both references matter when explaining a block.
    CONSTRAINT sanctioned_party_unique UNIQUE (name, list_reference)
);

-- Every decision this service has made.
--
-- Written before the answer is returned, so a decision a caller holds is always one that exists
-- here. The reference is the primary key rather than a surrogate: payments retries a timed-out
-- screening, and the second attempt has to find the first answer rather than produce a new one.
CREATE TABLE screening_decision (
    reference      VARCHAR(64)              PRIMARY KEY,
    customer_id    UUID                     NOT NULL,
    amount_minor   BIGINT                   NOT NULL,
    currency       VARCHAR(3)               NOT NULL,
    beneficiary    VARCHAR(200)             NOT NULL,
    outcome        VARCHAR(16)              NOT NULL,
    reason         VARCHAR(500)             NOT NULL,
    screened_at    TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT screening_outcome_known
        CHECK (outcome IN ('ALLOW', 'REVIEW', 'BLOCK'))
);

-- The daily running total is a sum over this index: one customer, one day, excluding blocks.
-- Without it the limit check degrades to a sequential scan that grows with the table.
CREATE INDEX screening_decision_customer_day_idx
    ON screening_decision (customer_id, screened_at);

COMMENT ON TABLE screening_decision IS
    'Every decision made, kept for audit and for replaying a retried screening.';

-- A starting sanctions list, so a clean clone screens against something rather than nothing.
--
-- These are invented names chosen to exercise the matcher, not real sanctions data: one with
-- Scandinavian letters that normalisation has to fold, and one that a shared surname should send
-- for review rather than block.
INSERT INTO sanctioned_party (id, name, list_reference) VALUES
    ('6f1d2c3a-0b4e-4f5a-9c7d-1e2f3a4b5c6d', 'Åse Bjørk',   'DEMO-EU-2026/114'),
    ('7a2e3d4b-1c5f-4a6b-8d9e-2f3a4b5c6d7e', 'Ivan Petrov', 'DEMO-UN-1267/8821'),
    ('8b3f4e5c-2d6a-4b7c-9e0f-3a4b5c6d7e8f', 'Nordvik Handel AS', 'DEMO-EU-2026/207');
