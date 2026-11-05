package org.sitenetsoft.quarkus.social;

import java.util.regex.Pattern;

/** A social network, identified by a lower-case id such as {@code activitypub}. */
public record Network(String id) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");

    public static final Network ACTIVITYPUB = new Network("activitypub");
    public static final Network ATPROTO = new Network("atproto");

    public Network {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("network id must match [a-z][a-z0-9-]*: " + id);
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
