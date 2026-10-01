package io.lara.accounts.infrastructure.rest;

import java.net.URI;
import java.util.Currency;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.lara.accounts.application.OpenAccount;
import io.lara.accounts.application.RegisterCustomer;
import io.lara.accounts.application.ViewAccounts;
import io.lara.accounts.domain.Account;
import io.lara.accounts.domain.Customer;
import io.lara.accounts.domain.CustomerId;
import io.lara.accounts.domain.Iban;
import io.lara.accounts.infrastructure.rest.AccountDtos.AccountResponse;
import io.lara.accounts.infrastructure.rest.AccountDtos.BalanceResponse;
import io.lara.accounts.infrastructure.rest.AccountDtos.CustomerResponse;
import io.lara.accounts.infrastructure.rest.AccountDtos.OpenAccountRequest;
import io.lara.accounts.infrastructure.rest.AccountDtos.RegisterCustomerRequest;

/**
 * The accounts service's HTTP surface.
 *
 * <p>A thin adapter, as in the ledger: wire shapes in, domain types out, no business rule decided
 * here. Balances are served from the projection and never by calling the ledger — that is the
 * point of having a projection at all.
 */
@Path("/accounts-api")
@Produces(MediaType.APPLICATION_JSON)
public class AccountResource {

    private final RegisterCustomer registerCustomer;
    private final OpenAccount openAccount;
    private final ViewAccounts viewAccounts;

    public AccountResource(RegisterCustomer registerCustomer, OpenAccount openAccount, ViewAccounts viewAccounts) {
        this.registerCustomer = registerCustomer;
        this.openAccount = openAccount;
        this.viewAccounts = viewAccounts;
    }

    @POST
    @Path("/customers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response register(@Valid RegisterCustomerRequest request) {
        Customer customer = registerCustomer.register(request.name());

        return Response.status(Response.Status.CREATED)
                .location(URI.create("/accounts-api/customers/" + customer.id()))
                .entity(CustomerResponse.of(customer))
                .build();
    }

    /** Records the outcome of identity checks. Until this passes, no account becomes usable. */
    @POST
    @Path("/customers/{id}/verification")
    public CustomerResponse verify(@PathParam("id") String id) {
        return CustomerResponse.of(registerCustomer.verify(CustomerId.of(id)));
    }

    @GET
    @Path("/customers/{id}/accounts")
    public List<AccountResponse> accountsOf(@PathParam("id") String id) {
        return viewAccounts.of(CustomerId.of(id)).stream().map(AccountResponse::of).toList();
    }

    /**
     * Opens an account. It starts in {@code OPENING} and is activated separately, because in a
     * real bank those are different decisions with different approvals behind them.
     */
    @POST
    @Path("/accounts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response open(@Valid OpenAccountRequest request) {
        Account account = openAccount.open(
                CustomerId.of(request.customerId()),
                Currency.getInstance(request.currency()),
                request.ledgerAccountId());

        return Response.status(Response.Status.CREATED)
                .location(URI.create("/accounts-api/accounts/" + account.iban()))
                .entity(AccountResponse.of(account))
                .build();
    }

    @POST
    @Path("/accounts/{iban}/activation")
    public AccountResponse activate(@PathParam("iban") String iban) {
        return AccountResponse.of(openAccount.activate(Iban.of(iban)));
    }

    @GET
    @Path("/accounts/{iban}")
    public AccountResponse byIban(@PathParam("iban") String iban) {
        return AccountResponse.of(viewAccounts.byIban(Iban.of(iban)));
    }

    /**
     * The balance, from the projection.
     *
     * <p>The response states {@code asOf} and {@code staleSeconds} rather than presenting the
     * number as current. A client that cannot see the age of an asynchronously-fed figure will
     * assume it is live, which is wrong exactly when the consumer is behind.
     */
    @GET
    @Path("/accounts/{iban}/balance")
    public BalanceResponse balance(@PathParam("iban") String iban) {
        return BalanceResponse.of(viewAccounts.balanceOf(Iban.of(iban)));
    }
}
