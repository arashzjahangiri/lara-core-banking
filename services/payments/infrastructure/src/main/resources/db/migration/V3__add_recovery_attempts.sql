-- How many times recovery has re-driven a saga.
--
-- Needed so a transfer that keeps failing stops being picked up. Without a counter the sweep
-- would retry a permanently broken saga every interval forever, and the one genuinely stuck
-- payment that needs a person would be buried in its own log noise.
--
-- Separate from the version column, which counts every write. This counts only resumptions,
-- because that is what the give-up rule is about: a saga advanced fifty times by a healthy
-- orchestrator is healthy, and one resumed four times is not.
ALTER TABLE transfer
    ADD COLUMN recovery_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE transfer
    ADD CONSTRAINT transfer_recovery_attempts_not_negative CHECK (recovery_attempts >= 0);

COMMENT ON COLUMN transfer.recovery_attempts IS
    'Times the recovery sweep has resumed this saga. Past the configured cap it moves to FAILED.';