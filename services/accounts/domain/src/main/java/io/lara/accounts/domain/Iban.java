package io.lara.accounts.domain;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An International Bank Account Number, validated by its check digits.
 *
 * <p>The check is the MOD-97 algorithm from ISO 13616: move the first four characters to the end,
 * replace each letter with two digits (A=10 … Z=35), and read the result as one enormous integer.
 * A valid IBAN leaves remainder 1 when divided by 97.
 *
 * <p>It catches a single mistyped character and almost every transposition, which is the entire
 * reason it exists — an IBAN is usually typed or pasted by a human, and a payment sent to a
 * plausible-looking wrong account is expensive to recall.
 *
 * <p>Twenty lines of arithmetic. A banking system that accepts invalid IBANs undermines
 * everything else it claims about correctness.
 */
public record Iban(String value) implements Comparable<Iban> {

    private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{1,30}");
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);
    private static final int MOVED_PREFIX_LENGTH = 4;

    /**
     * Total length per country. IBANs are fixed-length by country, so a Danish IBAN of 19
     * characters is wrong even if its check digits happen to work out.
     */
    private static final Map<String, Integer> LENGTH_BY_COUNTRY = Map.of(
            "DK", 18, "DE", 22, "FR", 27, "GB", 22, "NL", 18,
            "SE", 24, "NO", 15, "ES", 24, "IT", 27, "BE", 16);

    public Iban {
        Objects.requireNonNull(value, "IBAN must not be null");
        value = value.replace(" ", "").toUpperCase(Locale.ROOT);

        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("not an IBAN: '" + value + "'");
        }
        Integer expectedLength = LENGTH_BY_COUNTRY.get(value.substring(0, 2));
        if (expectedLength != null && value.length() != expectedLength) {
            throw new IllegalArgumentException(
                    "a " + value.substring(0, 2) + " IBAN is " + expectedLength
                            + " characters, was " + value.length());
        }
        if (!hasValidCheckDigits(value)) {
            throw new IllegalArgumentException("IBAN check digits do not match: '" + value + "'");
        }
    }

    public static Iban of(String value) {
        return new Iban(value);
    }

    /**
     * Builds an IBAN from a country, bank code and account number, computing the check digits.
     *
     * <p>Used when opening an account. Computing them is the same arithmetic as checking them: the
     * digits are whatever makes the remainder come out at 1.
     */
    public static Iban generate(String countryCode, String bankCode, String accountNumber) {
        Objects.requireNonNull(countryCode, "country code must not be null");
        Objects.requireNonNull(bankCode, "bank code must not be null");
        Objects.requireNonNull(accountNumber, "account number must not be null");

        String body = (bankCode + accountNumber).toUpperCase(Locale.ROOT).replace(" ", "");
        String country = countryCode.toUpperCase(Locale.ROOT);

        // Check digits of "00" first, then solve for the value that makes the remainder 1.
        int check = 98 - toNumeric(body + country + "00").mod(NINETY_SEVEN).intValue();
        return new Iban(country + String.format("%02d", check) + body);
    }

    public String countryCode() {
        return value.substring(0, 2);
    }

    /** Grouped in fours, which is how an IBAN is printed on a statement. */
    public String formatted() {
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < value.length(); i += 4) {
            if (i > 0) {
                grouped.append(' ');
            }
            grouped.append(value, i, Math.min(i + 4, value.length()));
        }
        return grouped.toString();
    }

    private static boolean hasValidCheckDigits(String iban) {
        String rearranged = iban.substring(MOVED_PREFIX_LENGTH) + iban.substring(0, MOVED_PREFIX_LENGTH);
        return toNumeric(rearranged).mod(NINETY_SEVEN).intValue() == 1;
    }

    /** Letters become two digits each: A=10 through Z=35. Digits stay as they are. */
    private static BigInteger toNumeric(String rearranged) {
        StringBuilder digits = new StringBuilder(rearranged.length() * 2);
        for (char c : rearranged.toCharArray()) {
            if (Character.isLetter(c)) {
                digits.append(Character.getNumericValue(c));
            } else {
                digits.append(c);
            }
        }
        return new BigInteger(digits.toString());
    }

    @Override
    public int compareTo(Iban other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
