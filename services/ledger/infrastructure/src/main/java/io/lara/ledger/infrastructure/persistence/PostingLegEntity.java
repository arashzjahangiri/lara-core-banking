package io.lara.ledger.infrastructure.persistence;

import java.util.Currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.EntrySide;
import io.lara.ledger.domain.Money;
import io.lara.ledger.domain.PostingLeg;

/**
 * The persistence shape of one leg.
 *
 * <p>{@link Money} is stored as two columns — a {@code BIGINT} of minor units and the currency code
 * — because that is what it is. There is no decimal column and no floating point anywhere near it.
 *
 * <p>{@code legIndex} preserves the order the legs were written in. Order carries no accounting
 * meaning, but reproducing a transaction exactly as recorded matters for an audit trail, and a
 * database is free to return rows in any order without it.
 */
@Entity
@Table(name = "posting_leg")
public class PostingLegEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransactionEntity transaction;

    @Column(name = "leg_index", nullable = false)
    private short legIndex;

    @Column(name = "account_id", length = 64, nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 6, nullable = false)
    private EntrySide side;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    protected PostingLegEntity() {
        // required by Hibernate
    }

    static PostingLegEntity from(LedgerTransactionEntity owner, int index, PostingLeg leg) {
        PostingLegEntity entity = new PostingLegEntity();
        entity.transaction = owner;
        entity.legIndex = (short) index;
        entity.accountId = leg.account().value();
        entity.side = leg.side();
        entity.amountMinor = leg.amount().minorUnits();
        entity.currency = leg.amount().currency().getCurrencyCode();
        return entity;
    }

    PostingLeg toDomain() {
        return new PostingLeg(
                AccountId.of(accountId),
                side,
                Money.of(amountMinor, Currency.getInstance(currency.trim())));
    }

    short legIndex() {
        return legIndex;
    }
}
