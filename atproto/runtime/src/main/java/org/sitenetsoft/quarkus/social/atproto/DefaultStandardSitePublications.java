package org.sitenetsoft.quarkus.social.atproto;

import java.net.URI;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.quarkus.social.Post;

import io.quarkus.arc.DefaultBean;

/** The default {@link StandardSitePublications}: the origin of the post's canonical URL as an {@code https} site. */
@DefaultBean
@ApplicationScoped
public class DefaultStandardSitePublications implements StandardSitePublications {

    /** {@code https://<host>[:port]} of {@code post.url()}; empty for a post without a URL. */
    @Override
    public Optional<URI> site(String account, Post post) {
        return post.url().map(DefaultStandardSitePublications::origin);
    }

    static URI origin(URI url) {
        return URI.create("https://" + url.getRawAuthority().replaceFirst("^.*@", ""));
    }
}
