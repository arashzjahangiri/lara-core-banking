package io.lara.accounts.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.lara.accounts.domain.Customer;
import io.lara.accounts.domain.CustomerId;

@Entity
@Table(name = "customer")
public class CustomerEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", length = 16, nullable = false)
    private Customer.KycStatus kycStatus;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected CustomerEntity() {
        // required by Hibernate
    }

    static CustomerEntity from(Customer customer) {
        CustomerEntity entity = new CustomerEntity();
        entity.id = customer.id().value();
        entity.apply(customer);
        return entity;
    }

    void apply(Customer customer) {
        this.name = customer.name();
        this.kycStatus = customer.kyc();
    }

    Customer toDomain() {
        return new Customer(CustomerId.of(id), name, kycStatus);
    }
}
