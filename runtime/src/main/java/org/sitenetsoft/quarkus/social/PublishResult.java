package org.sitenetsoft.quarkus.social;

import java.util.Map;

/** One {@link NetworkResult} per network for a published post. */
public record PublishResult(String postId, Map<Network, NetworkResult> results) {

    public PublishResult {
        results = Map.copyOf(results);
    }

    public NetworkResult result(Network network) {
        NetworkResult r = results.get(network);
        if (r == null) {
            throw new IllegalArgumentException("no result for network " + network);
        }
        return r;
    }

    public boolean allPublished() {
        return results.values().stream().allMatch(r -> r instanceof NetworkResult.Published);
    }
}
