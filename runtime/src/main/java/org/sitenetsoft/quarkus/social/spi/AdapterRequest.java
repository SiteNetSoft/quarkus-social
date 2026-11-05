package org.sitenetsoft.quarkus.social.spi;

import java.util.Optional;

import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.content.PostContent;
import org.sitenetsoft.quarkus.social.store.NetworkState;

/**
 * One call to a {@link SocialAdapter}.
 *
 * @param account the app account
 * @param identity the account's identity on this network, from {@link SocialAdapter#linkedIdentity(String)}
 * @param post the post; for {@code delete}, the stored snapshot
 * @param content the post's sanitized HTML and plain text
 * @param previous what an earlier call stored for this post and network, so a retry writes only the missing parts
 */
public record AdapterRequest(String account, String identity, Post post, PostContent content,
        Optional<NetworkState> previous) {
}
