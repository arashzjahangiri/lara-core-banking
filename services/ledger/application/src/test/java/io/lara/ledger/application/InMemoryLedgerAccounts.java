package io.lara.ledger.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerAccount;

/**
 * A fake, not a mock. Twelve lines, no framework, and it behaves like the real thing rather than
 * replaying a recorded script — which is what lets a test say "this account does not exist" without
 * stubbing a method call.
 */
final class InMemoryLedgerAccounts implements LedgerAccounts {

    private final Map<AccountId, LedgerAccount> accounts = new LinkedHashMap<>();

    InMemoryLedgerAccounts with(LedgerAccount... open) {
        for (LedgerAccount account : open) {
            accounts.put(account.id(), account);
        }
        return this;
    }

    @Override
    public Optional<LedgerAccount> findById(AccountId id) {
        return Optional.ofNullable(accounts.get(id));
    }
}
