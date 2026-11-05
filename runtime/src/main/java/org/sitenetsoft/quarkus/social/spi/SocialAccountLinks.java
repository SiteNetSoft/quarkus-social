package org.sitenetsoft.quarkus.social.spi;

import java.util.Optional;

/**
 * Optional application SPI: which network identities an app account publishes as, and back. No module ships a bean
 * of this type; when the app provides one, the adapters use it instead of their own default lookup.
 */
public interface SocialAccountLinks {

    Optional<String> activityPubUsername(String account);

    Optional<String> atprotoDid(String account);

    Optional<String> accountForActivityPubUsername(String username);

    Optional<String> accountForAtprotoDid(String did);
}
