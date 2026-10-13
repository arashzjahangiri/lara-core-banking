package io.lara.payments.fakes;

import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.lara.payments.application.AccountDirectory;
import io.lara.payments.application.AccountSummary;
import io.lara.payments.domain.Iban;

/**
 * A directory the test controls.
 *
 * <p>Stands in for the accounts service so the saga can get past its first step. It deliberately
 * does nothing clever — an account either exists here or it does not. The interesting behaviour
 * under test is the orchestrator''s, and a fake with opinions of its own only obscures it.
 */
// No @Priority. A @Priority-annotated alternative is enabled application-wide, which would
// replace the real adapter in every test — including ResilienceTest, whose entire subject is
// what happens when the real adapter cannot reach anything. Enabled per-profile instead, by
// StubbedDependenciesProfile#getEnabledAlternatives.
@Alternative
@ApplicationScoped
public class FixedAccounts implements AccountDirectory {

    private final Map<Iban, AccountSummary> known = new ConcurrentHashMap<>();

    public void register(Iban iban, String customerId, String ledgerAccount, Currency currency) {
        known.put(iban, new AccountSummary(iban, customerId, ledgerAccount, currency, true));
    }

    public void clear() {
        known.clear();
    }

    @Override
    public Optional<AccountSummary> find(Iban iban) {
        return Optional.ofNullable(known.get(iban));
    }
}