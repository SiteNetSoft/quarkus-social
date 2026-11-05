package org.sitenetsoft.quarkus.social.test;

import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.PublishResult;
import org.sitenetsoft.quarkus.social.SkipReason;

/** Assertions on a {@link PublishResult}; they throw {@link AssertionError} (no test framework needed). */
public final class SocialAssertions {

    private SocialAssertions() {
    }

    /** The network's result is {@code Published}; returns it. */
    public static NetworkResult.Published assertPublished(PublishResult result, Network network) {
        return as(result, network, NetworkResult.Published.class);
    }

    /** The network's result is {@code Skipped} for {@code reason}; returns it. */
    public static NetworkResult.Skipped assertSkipped(PublishResult result, Network network, SkipReason reason) {
        NetworkResult.Skipped skipped = as(result, network, NetworkResult.Skipped.class);
        if (skipped.reason() != reason) {
            throw new AssertionError(network + ": expected Skipped(" + reason + ") but was " + skipped);
        }
        return skipped;
    }

    /** The network's result is {@code Failed} with {@code code}; returns it. */
    public static NetworkResult.Failed assertFailed(PublishResult result, Network network, FailureCode code) {
        NetworkResult.Failed failed = as(result, network, NetworkResult.Failed.class);
        if (failed.code() != code) {
            throw new AssertionError(network + ": expected Failed(" + code + ") but was " + failed);
        }
        return failed;
    }

    /** The result has no entry for the network (it wasn't targeted). */
    public static void assertNotTargeted(PublishResult result, Network network) {
        if (result.results().containsKey(network)) {
            throw new AssertionError(network + ": expected no result but was " + result.results().get(network));
        }
    }

    private static <T extends NetworkResult> T as(PublishResult result, Network network, Class<T> type) {
        NetworkResult r = result.results().get(network);
        if (r == null) {
            throw new AssertionError(network + ": no result in " + result);
        }
        if (!type.isInstance(r)) {
            throw new AssertionError(network + ": expected " + type.getSimpleName() + " but was " + r);
        }
        return type.cast(r);
    }
}
