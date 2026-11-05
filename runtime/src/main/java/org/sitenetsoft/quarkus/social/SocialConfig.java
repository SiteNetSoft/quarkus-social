package org.sitenetsoft.quarkus.social;

import java.time.Duration;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.social")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface SocialConfig {

    /**
     * How long one adapter call (link lookup included) may take before its network is reported
     * {@code Failed(TIMEOUT)}, retryable.
     */
    @WithDefault("30s")
    Duration timeout();
}
