package io.lara.payments.infrastructure.client;

import java.util.Currency;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.lara.payments.application.AccountDirectory;
import io.lara.payments.application.AccountSummary;
import io.lara.payments.application.RemoteServiceException;
import io.lara.payments.domain.Iban;

/**
 * Looks an account up in the accounts service.
 *
 * <p>A 404 becomes an empty {@code Optional}, because "no such account" is an answer. Anything
 * else — a timeout, a 500, an unparseable body — becomes a {@link RemoteServiceException},
 * because not being able to ask is not the same as being told no. Collapsing the two would
 * reject every transfer for the length of an outage.
 */
@ApplicationScoped
public class RestAccountDirectory implements AccountDirectory {

    private static final String SERVICE = "accounts";

    /** The one status in which an account may take part in a transfer. */
    private static final String OPEN = "OPEN";

    private final AccountsClient accounts;

    public RestAccountDirectory(@RestClient AccountsClient accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<AccountSummary> find(Iban iban) {
        try {
            AccountsClient.AccountResponse response = accounts.byIban(iban.value());

            return Optional.of(new AccountSummary(
                    Iban.of(response.iban()),
                    response.customerId(),
                    response.ledgerAccountId(),
                    Currency.getInstance(response.currency()),
                    OPEN.equals(response.status())));

        } catch (WebApplicationException answered) {
            if (answered.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                return Optional.empty();
            }
            throw new RemoteServiceException(SERVICE,
                    "looking up " + iban + " failed with " + answered.getResponse().getStatus(), answered);

        } catch (ProcessingException unreachable) {
            throw new RemoteServiceException(SERVICE, "could not look up " + iban, unreachable);
        }
    }
}