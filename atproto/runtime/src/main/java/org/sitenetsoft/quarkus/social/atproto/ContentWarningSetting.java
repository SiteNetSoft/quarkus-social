package org.sitenetsoft.quarkus.social.atproto;

import java.util.List;
import java.util.Optional;

/** The parsed {@code quarkus.social.atproto.content-warning} setting. */
record ContentWarningSetting(Mode mode, Optional<String> label) {

    static final List<String> LABELS = List.of("sexual", "nudity", "porn", "graphic-media");
    private static final String LABEL_PREFIX = "label:";

    enum Mode {
        SKIP,
        DROP,
        LABEL
    }

    /** @throws IllegalArgumentException for anything but {@code skip}, {@code drop} or {@code label:<known value>} */
    static ContentWarningSetting parse(String value) {
        if ("skip".equals(value)) {
            return new ContentWarningSetting(Mode.SKIP, Optional.empty());
        }
        if ("drop".equals(value)) {
            return new ContentWarningSetting(Mode.DROP, Optional.empty());
        }
        if (value != null && value.startsWith(LABEL_PREFIX) && LABELS.contains(value.substring(LABEL_PREFIX.length()))) {
            return new ContentWarningSetting(Mode.LABEL, Optional.of(value.substring(LABEL_PREFIX.length())));
        }
        throw new IllegalArgumentException("quarkus.social.atproto.content-warning must be skip, drop or label:<"
                + String.join("|", LABELS) + ">, not '" + value + "'");
    }
}
