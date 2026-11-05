package org.sitenetsoft.quarkus.social.atproto;

import java.util.List;
import java.util.Optional;

/** The parsed {@code quarkus.social.atproto.content-warning} setting (parsed once, at startup). */
public record ContentWarningSetting(Mode mode, Optional<String> label) {

    static final List<String> LABELS = List.of("sexual", "nudity", "porn", "graphic-media");
    private static final String LABEL_PREFIX = "label:";

    public enum Mode {
        SKIP,
        DROP,
        LABEL
    }

    /** @throws IllegalArgumentException for anything but {@code skip}, {@code drop} or {@code label:<known value>} */
    public static ContentWarningSetting parse(String value) {
        if ("skip".equals(value)) {
            return new ContentWarningSetting(Mode.SKIP, Optional.empty());
        }
        if ("drop".equals(value)) {
            return new ContentWarningSetting(Mode.DROP, Optional.empty());
        }
        String label = value != null && value.startsWith(LABEL_PREFIX) ? value.substring(LABEL_PREFIX.length()) : null;
        if (LABELS.contains(label)) {
            return new ContentWarningSetting(Mode.LABEL, Optional.of(label));
        }
        throw new IllegalArgumentException("quarkus.social.atproto.content-warning must be skip, drop or label:<"
                + String.join("|", LABELS) + ">, not '" + value + "'");
    }
}
