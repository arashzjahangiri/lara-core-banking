-- The bank's own accounts.
--
-- These are not decoration. A customer deposit is a liability, so something must be debited
-- against it — that is BANK.CASH. A fee has to be credited somewhere the bank owns — that is
-- BANK.FEE.INCOME. Without accounts on the bank's side of the balance sheet, a transfer carrying
-- a fee cannot be expressed at all and the books stop balancing.
--
-- Ids mirror the SystemAccount enum: <prefix>.<ISO 4217 code>. An integration test asserts that
-- every enum value exists here for every seeded currency, so the two cannot drift apart.
--
-- Seeded for EUR and USD. Adding a currency means adding its four rows in a new migration, never
-- editing this one.

INSERT INTO ledger_account (id, account_class, currency) VALUES
    -- Cash the bank holds, at the central bank or a correspondent. Rises as deposits arrive.
    ('BANK.CASH.EUR',       'ASSET',  'EUR'),
    ('BANK.CASH.USD',       'ASSET',  'USD'),

    -- Fees the bank has earned.
    ('BANK.FEE.INCOME.EUR', 'INCOME', 'EUR'),
    ('BANK.FEE.INCOME.USD', 'INCOME', 'USD'),

    -- Money in flight: sent to a scheme but not yet settled.
    ('BANK.CLEARING.EUR',   'ASSET',  'EUR'),
    ('BANK.CLEARING.USD',   'ASSET',  'USD'),

    -- Money received that cannot yet be allocated to a customer. Should trend to zero.
    ('BANK.SUSPENSE.EUR',   'ASSET',  'EUR'),
    ('BANK.SUSPENSE.USD',   'ASSET',  'USD');
