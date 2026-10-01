package io.lara.accounts.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Where an account is in its life.
 *
 * <p>The transitions are part of the model rather than a convention, because the illegal ones
 * matter: an account that has been closed must never reopen. Its IBAN may have been reissued, its
 * balance settled and its closure reported — reopening it would silently resurrect all of that.
 *
 * <p>A closed account is therefore terminal. Restoring a customer's banking means opening a new
 * account, which leaves both facts on the record.
 */
public enum AccountStatus {

    /** Opened but not yet usable: identity checks outstanding. Can receive nothing. */
    OPENING,

    /** Usable. The only state in which money moves. */
    OPEN,

    /** Blocked — a sanctions hit, a dispute, a court order. Reversible. */
    FROZEN,

    /** Terminal. Never reopens. */
    CLOSED;

    private static final Set<AccountStatus> FROM_OPENING = Set.of(OPEN, CLOSED);
    private static final Set<AccountStatus> FROM_OPEN = Set.of(FROZEN, CLOSED);
    private static final Set<AccountStatus> FROM_FROZEN = Set.of(OPEN, CLOSED);

    public boolean canMoveTo(AccountStatus next) {
        Objects.requireNonNull(next, "next status must not be null");
        return switch (this) {
            case OPENING -> FROM_OPENING.contains(next);
            case OPEN -> FROM_OPEN.contains(next);
            case FROZEN -> FROM_FROZEN.contains(next);
            case CLOSED -> false;
        };
    }

    /** Whether money may move on an account in this state. Only one state allows it. */
    public boolean permitsMovement() {
        return this == OPEN;
    }

    public boolean isTerminal() {
        return this == CLOSED;
    }
}
