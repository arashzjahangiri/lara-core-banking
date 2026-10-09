package io.lara.payments.application;

import java.util.Optional;

import io.lara.payments.domain.Iban;

/**
 * The accounts service, as this service needs it.
 *
 * <p>A port rather than an HTTP client, which is what lets the whole orchestrator — including
 * every failure branch — be driven in a unit test with no network. The HTTP adapter lives in
 * infrastructure and no type from it appears in any signature above this line.
 *
 * <p>Empty means the account does not exist. A dependency being unreachable is not empty: that
 * throws {@link RemoteServiceException}, because "we could not ask" and "the answer is no" are
 * different facts and conflating them would reject a valid transfer during an outage.
 */
public interface AccountDirectory {

    Optional<AccountSummary> find(Iban iban);
}