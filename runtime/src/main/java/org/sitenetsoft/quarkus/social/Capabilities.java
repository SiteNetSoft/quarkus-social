package org.sitenetsoft.quarkus.social;

import java.util.OptionalInt;
import java.util.Set;

/** What a network adapter can do. */
public record Capabilities(Set<Visibility> visibilities, Set<Kind> kinds, boolean edits, boolean deletes,
        boolean contentWarnings, OptionalInt maxTextGraphemes, boolean html, boolean interactions) {

    public Capabilities {
        visibilities = Set.copyOf(visibilities);
        kinds = Set.copyOf(kinds);
    }
}
