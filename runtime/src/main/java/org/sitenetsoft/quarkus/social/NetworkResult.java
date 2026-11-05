package org.sitenetsoft.quarkus.social;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/** The outcome of publishing one post to one network. */
public sealed interface NetworkResult permits NetworkResult.Published, NetworkResult.Skipped, NetworkResult.Failed {

    Network network();

    record Published(Network network, Optional<URI> url, Map<String, String> refs, Optional<String> note,
            boolean resent) implements NetworkResult {
        public Published {
            refs = Map.copyOf(refs);
        }
    }

    record Skipped(Network network, SkipReason reason, String detail) implements NetworkResult {
    }

    record Failed(Network network, FailureCode code, String reason, boolean retryable, Map<String, String> refs)
            implements NetworkResult {
        public Failed {
            refs = Map.copyOf(refs);
        }
    }
}
