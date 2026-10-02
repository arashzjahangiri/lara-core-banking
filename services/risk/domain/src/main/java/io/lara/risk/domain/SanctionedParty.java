package io.lara.risk.domain;

import java.util.Objects;

/**
 * One entry on a sanctions list.
 *
 * <p>The list reference travels with the name because a block has to be explainable to the person
 * whose payment was stopped, and "your beneficiary is on a list" is not an explanation. Naming
 * the list and the entry is what lets a compliance officer check the match and, when it is wrong,
 * say so with something to point at.
 */
public record SanctionedParty(PartyName name, String listReference) {

    public SanctionedParty {
        Objects.requireNonNull(name, "a sanctioned party must have a name");
        Objects.requireNonNull(listReference, "a sanctioned party must cite the list it came from");
        listReference = listReference.trim();
        if (listReference.isEmpty()) {
            throw new IllegalArgumentException("a sanctioned party must cite the list it came from");
        }
    }

    public static SanctionedParty of(String name, String listReference) {
        return new SanctionedParty(PartyName.of(name), listReference);
    }

    @Override
    public String toString() {
        return name + " (" + listReference + ")";
    }
}