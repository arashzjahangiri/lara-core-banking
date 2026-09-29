-- Demo customer accounts for the local stack.
--
-- These are NOT in the production image, and that is deliberate. quarkus.flyway.locations is a
-- build-time property, so the db/demo migrations are packaged only under the dev and test
-- profiles; a production build carries db/migration alone. Demo data therefore cannot reach a
-- real deployment even if someone sets the environment variable.
--
-- Compose seeds them here instead, after the ledger has migrated the schema.

INSERT INTO ledger_account (id, account_class, currency) VALUES
    ('CUSTOMER000001', 'LIABILITY', 'EUR'),
    ('CUSTOMER000002', 'LIABILITY', 'EUR'),
    ('CUSTOMER000003', 'LIABILITY', 'EUR'),
    ('CUSTOMER000010', 'LIABILITY', 'USD')
ON CONFLICT (id) DO NOTHING;
