package io.lara.risk.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The names money may not be sent to.
 *
 * <p>Held as a list rather than compiled in, for the same reason limits are: a sanctions list
 * changes when a regulator publishes a change, sometimes the same day, and a list that needs a
 * deployment to update is a list that is wrong for as long as the release takes.
 *
 * <p>The outcome is graded, which matters more here than anywhere else in the service. An exact
 * match on every part of a listed name blocks. A shared surname only sends the transfer for
 * review, because thousands of people share a surname with someone on a sanctions list and
 * blocking all of them is both wrong and, in practice, how screening gets switched off.
 */
public final class SanctionsList {

    private final List<SanctionedParty> parties;

    public SanctionsList(List<SanctionedParty> parties) {
        this.parties = List.copyOf(Objects.requireNonNull(parties, "parties must not be null"));
    }

    /** An empty list. Screens everything as allowed, which is the honest answer for no data. */
    public static SanctionsList empty() {
        return new SanctionsList(List.of());
    }

    /**
     * Screens one beneficiary.
     *
     * <p>Full matches are searched for before partial ones, so a name that matches one entry
     * completely and another only partly blocks rather than going for review. Returning the
     * strictest available answer is the same rule {@link ScreeningDecision#mostRestrictive} uses
     * between checks, applied within this one.
     */
    public ScreeningDecision screen(PartyName beneficiary) {
        Objects.requireNonNull(beneficiary, "beneficiary must not be null");

        Optional<SanctionedParty> exact = parties.stream()
                .filter(party -> beneficiary.containsAllTokensOf(party.name()))
                .findFirst();
        if (exact.isPresent()) {
            SanctionedParty party = exact.get();
            return ScreeningDecision.block(
                    "beneficiary matches sanctioned party '" + party.name() + "' ("
                            + party.listReference() + ")");
        }

        Optional<SanctionedParty> partial = parties.stream()
                .filter(party -> beneficiary.sharesASignificantTokenWith(party.name()))
                .findFirst();
        if (partial.isPresent()) {
            SanctionedParty party = partial.get();
            return ScreeningDecision.review(
                    "beneficiary partially matches sanctioned party '" + party.name() + "' ("
                            + party.listReference() + "); a shared name is not a match");
        }

        return ScreeningDecision.allow("no sanctions match");
    }

    public int size() {
        return parties.size();
    }

    public boolean isEmpty() {
        return parties.isEmpty();
    }
}
