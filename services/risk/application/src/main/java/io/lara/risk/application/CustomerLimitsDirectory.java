package io.lara.risk.application;

import java.util.Optional;

import io.lara.risk.domain.CustomerId;
import io.lara.risk.domain.CustomerLimits;

/**
 * Where a customer's thresholds come from.
 *
 * <p>Empty is a real answer rather than an error. Most customers have no row of their own and run
 * on the service's default, and only the ones a risk officer has looked at individually are
 * configured here. Treating "no row" as a failure would mean every new customer's first payment
 * broke.
 */
public interface CustomerLimitsDirectory {

    Optional<CustomerLimits> forCustomer(CustomerId customer);
}