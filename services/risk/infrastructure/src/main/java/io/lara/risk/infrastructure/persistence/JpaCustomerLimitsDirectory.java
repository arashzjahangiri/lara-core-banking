package io.lara.risk.infrastructure.persistence;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import io.lara.risk.application.CustomerLimitsDirectory;
import io.lara.risk.domain.CustomerId;
import io.lara.risk.domain.CustomerLimits;

/** Reads a customer's own thresholds, if a risk officer has set any. */
@ApplicationScoped
public class JpaCustomerLimitsDirectory implements CustomerLimitsDirectory {

    private final EntityManager entityManager;

    public JpaCustomerLimitsDirectory(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<CustomerLimits> forCustomer(CustomerId customer) {
        return Optional.ofNullable(entityManager.find(CustomerLimitsEntity.class, customer.value()))
                .map(CustomerLimitsEntity::toDomain);
    }
}