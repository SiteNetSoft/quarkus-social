package org.sitenetsoft.quarkus.social.atproto.deployment;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.atproto.StandardSitePublications;

/**
 * The app's {@link StandardSitePublications}: no publication (the adapter's default site), but a test can run code
 * once at that moment, which is after the Bluesky post was written and before the document is.
 */
@ApplicationScoped
public class HookedPublications implements StandardSitePublications {

    static final AtomicReference<Runnable> NEXT = new AtomicReference<>();

    @Override
    public Optional<URI> site(String account, Post post) {
        Runnable hook = NEXT.getAndSet(null);
        if (hook != null) {
            hook.run();
        }
        return Optional.empty();
    }
}
