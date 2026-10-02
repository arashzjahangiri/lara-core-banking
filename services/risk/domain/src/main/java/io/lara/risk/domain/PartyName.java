package io.lara.risk.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A party's name, reduced to something two spellings of it can be compared by.
 *
 * <p>The problem this solves is mundane and constant: the same person is written "Åse Bjørk" on
 * one system, "Ase Bjork" on another and "ÅSE  BJØRK" on a third. A sanctions list that only
 * matches the spelling it happens to store is a list that anyone evades by typing an accent.
 *
 * <p>So a name is normalised to comparable tokens — accents stripped, case folded, punctuation
 * dropped, whitespace collapsed. Scandinavian letters need the explicit substitutions below,
 * because Unicode decomposition does not split {@code ø} or {@code ß} into a base letter and a
 * mark the way it splits {@code é}.
 *
 * <h2>What this is not</h2>
 *
 * <p>Real sanctions screening is fuzzy: edit distance, phonetic keys, transliteration between
 * scripts, aliases and dates of birth. This is exact comparison of normalised tokens, which
 * catches the spelling and casing cases and nothing cleverer. That is a deliberate limit rather
 * than an oversight — a half-built fuzzy matcher would produce confident wrong answers, and the
 * {@code REVIEW} outcome exists precisely so partial matches reach a human instead.
 */
public record PartyName(String original, List<String> tokens) {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{Alnum}\\s]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Below this, a token is an initial or an abbreviation rather than a name. */
    private static final int SIGNIFICANT_TOKEN_LENGTH = 2;

    /**
     * Name particles, which carry no identifying weight on their own.
     *
     * <p>A length floor alone does not catch these — {@code van} and {@code der} are three
     * characters, the same as {@code Kim}, {@code Lee} and {@code Wu}, which are surnames that
     * must keep counting. So the exclusion is by name rather than by length.
     *
     * <p>Without it, one listed Dutch or Arabic name would send every beneficiary sharing its
     * particle for review, and a review queue that is mostly noise is a review queue nobody reads.
     *
     * <p>{@code al} and {@code ben} are genuinely ambiguous: both are particles and both are
     * names. They are excluded here, which trades a few missed partial matches for a queue that
     * stays usable. A full match on a complete listed name is unaffected either way.
     */
    private static final java.util.Set<String> NAME_PARTICLES = java.util.Set.of(
            "van", "von", "der", "den", "de", "del", "della", "di", "da", "dos", "du",
            "la", "le", "el", "al", "bin", "ibn", "ben", "abu", "the", "and", "of");

    public PartyName {
        Objects.requireNonNull(original, "name must not be null");
        Objects.requireNonNull(tokens, "tokens must not be null");
        tokens = List.copyOf(tokens);
    }

    public static PartyName of(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a party name must not be blank");
        }
        return new PartyName(trimmed, tokenise(trimmed));
    }

    /** Whether every token of {@code other} appears in this name. A full match on the listed name. */
    public boolean containsAllTokensOf(PartyName other) {
        Objects.requireNonNull(other, "other must not be null");
        return !other.tokens.isEmpty() && tokens.containsAll(other.tokens);
    }

    /**
     * Whether any token carrying real identifying weight is shared.
     *
     * <p>Particles and initials are filtered out first, so "van" alone is not a match. A shared
     * surname still is — and a shared surname is not proof of anything, which is exactly why this
     * feeds {@code REVIEW} rather than {@code BLOCK}.
     */
    public boolean sharesASignificantTokenWith(PartyName other) {
        Objects.requireNonNull(other, "other must not be null");
        return other.tokens.stream()
                .filter(PartyName::isSignificant)
                .anyMatch(tokens::contains);
    }

    private static boolean isSignificant(String token) {
        return token.length() >= SIGNIFICANT_TOKEN_LENGTH && !NAME_PARTICLES.contains(token);
    }

    /**
     * Strips a name down to comparable parts.
     *
     * <p>NFD decomposition separates a letter from its accent, so removing combining marks turns
     * {@code é} into {@code e}. Letters that are not decomposable — the Nordic {@code ø} and
     * {@code æ}, the German {@code ß} — have no mark to remove and are substituted explicitly.
     */
    private static List<String> tokenise(String name) {
        String folded = name.toLowerCase(Locale.ROOT)
                .replace("ø", "o")
                .replace("æ", "ae")
                .replace("å", "a")
                .replace("ß", "ss")
                .replace("đ", "d")
                .replace("ł", "l");

        String decomposed = Normalizer.normalize(folded, Normalizer.Form.NFD);
        String withoutAccents = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String wordsOnly = NON_ALPHANUMERIC.matcher(withoutAccents).replaceAll(" ");

        return WHITESPACE.splitAsStream(wordsOnly.trim())
                .filter(token -> !token.isEmpty())
                .toList();
    }

    @Override
    public String toString() {
        return original;
    }
}
