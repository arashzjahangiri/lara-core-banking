package io.lara.payments.fakes;

import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.lara.payments.application.AccountDirectory;
import io.lara.payments.application.AccountSummary;
import io.lara.payments.application.RemoteServiceException;
import io.lara.payments.domain.Iban;

/**
 * A directory the test controls.
 *
 * <p>Stands in for the accounts service so the saga can get past its first step.
 *
 * <p>It models the one distinction the whole phase turns on: <strong>an account that does not
 * exist is not the same as a service that cannot be reached</strong>. Empty means "no such
 * account" and the saga rejects the transfer; {@link #failWith} means "could not ask" and the
 * saga must stop without deciding anything.
 *
 * <p>That is not a detail. A fake that collapsed the two would make a test asserting "an outage
 * does not reject valid transfers" pass while the system did exactly that.
 */
// No @Priority. A @Priority-annotated alternative is enabled application-wide, which would
// replace the real adapter in every test — including ResilienceTest, whose entire subject is
// what happens when the real adapter cannot reach anything. Enabled per-profile instead, by
// StubbedDependenciesProfile#getEnabledAlternatives.
@Alternative
@ApplicationScoped
public class FixedAccounts implements AccountDirectory {

    private final Map<Iban, AccountSummary> known = new ConcurrentHashMap<>();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    public void register(Iban iban, String customerId, String ledgerAccount, Currency currency) {
        known.put(iban, new AccountSummary(iban, customerId, ledgerAccount, currency, true));
    }

    /** Registers an account that exists but cannot take part — frozen, closed, not yet verified. */
    public void registerInactive(Iban iban, String customerId, String ledgerAccount, Currency currency) {
        known.put(iban, new AccountSummary(iban, customerId, ledgerAccount, currency, false));
    }

    /** Forgets every account. The directory still answers; it simply knows nothing. */
    public void clear() {
        known.clear();
        failure.set(null);
    }

    /** Makes the service unreachable. Not the same as knowing nothing — see the class comment. */
    public void failWith(String why) {
        failure.set(new RemoteServiceException("accounts", why));
    }

    public void recover() {
        failure.set(null);
    }

    @Override
    public Optional<AccountSummary> find(Iban iban) {
        RuntimeException broken = failure.get();
        if (broken != null) {
            throw broken;
        }
        return Optional.ofNullable(known.get(iban));
    }
}