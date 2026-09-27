package io.lara.ledger.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The stable identifier of a ledger account.
 *
 * <p>A deliberate type rather than a bare {@code String}, so an account id cannot be passed where a
 * transaction reference or a customer id was meant. The compiler catches an entire class of
 * argument-ordering mistakes that are otherwise found in production.
 *
 * <p>Ids are uppercase, dot-separated segments: {@code BANK.FEE.INCOME}, {@code CUSTOMER.000123}.
 * The format is constrained so the value is safe to use in a URL and in a message key without
 * escaping, and so ids sort predictably.
 */
public record AccountId(String value) implements Comparable<AccountId> {

    private static final int MAX_LENGTH = 64;
    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9]+(\\.[A-Z0-9]+)*");

    public AccountId {
        Objects.requireNonNull(value, "account id must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("account id must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "account id must be at most " + MAX_LENGTH + " characters, was " + value.length());
        }
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "account id must be uppercase dot-separated alphanumerics, was '" + value + "'");
        }
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    @Override
    public int compareTo(AccountId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
