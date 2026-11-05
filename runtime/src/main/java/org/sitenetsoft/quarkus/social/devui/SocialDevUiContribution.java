package org.sitenetsoft.quarkus.social.devui;

import java.util.Map;

/**
 * Optional SPI for adapters: a titled block of plain data the "Social" Dev UI card shows next to the adapters (for
 * example the OAuth scope Bluesky publishing needs against the one configured). The core finds the beans with
 * {@code @All}, so it never depends on an adapter. Values must be strings, numbers, booleans, lists or maps, and
 * must not hold secrets.
 */
public interface SocialDevUiContribution {

    /** Heading of the block. */
    String title();

    /** What to show, in order. */
    Map<String, Object> data();
}
