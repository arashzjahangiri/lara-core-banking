-- Demo customer accounts.
--
-- Loaded only in dev and test, via quarkus.flyway.locations. Production runs db/migration alone,
-- so this data can never reach a real deployment — which is why it lives in its own location
-- rather than behind a flag in a real migration.
--
-- The high version number keeps it ordered after every real migration, whatever is added later.

INSERT INTO ledger_account (id, account_class, currency) VALUES
    ('CUSTOMER000001', 'LIABILITY', 'EUR'),
    ('CUSTOMER000002', 'LIABILITY', 'EUR'),
    ('CUSTOMER000003', 'LIABILITY', 'EUR'),
    ('CUSTOMER000010', 'LIABILITY', 'USD');
