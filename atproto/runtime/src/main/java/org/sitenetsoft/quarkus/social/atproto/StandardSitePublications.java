package org.sitenetsoft.quarkus.social.atproto;

import java.net.URI;
import java.util.Optional;

import org.sitenetsoft.quarkus.social.Post;

/**
 * Optional application SPI: the {@code site} of a post's {@code site.standard.document}. An app that keeps a
 * {@code site.standard.publication} answers its {@code at://} URI. The default answers the origin of the post's
 * canonical URL ({@code https://<host>[:port]}, a "loose document" the Lexicon allows); an empty answer from an app
 * bean means that default too. Called on a worker thread.
 */
public interface StandardSitePublications {

    Optional<URI> site(String account, Post post);
}
