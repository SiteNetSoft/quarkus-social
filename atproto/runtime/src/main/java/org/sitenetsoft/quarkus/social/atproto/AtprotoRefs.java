package org.sitenetsoft.quarkus.social.atproto;

import java.util.Map;
import java.util.Optional;

import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.atproto.syntax.Cid;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;
import org.sitenetsoft.quarkus.social.store.NetworkState;

import com.atproto.repo.StrongRef;

/**
 * The refs the adapter writes: {@code post.uri}/{@code post.cid} (the {@code app.bsky.feed.post}) and
 * {@code document.uri}/{@code document.cid} (the {@code site.standard.document}).
 */
final class AtprotoRefs {

    static final String POST_COLLECTION = "app.bsky.feed.post";
    static final String DOCUMENT_COLLECTION = "site.standard.document";
    static final Nsid POST_NSID = Nsid.parse(POST_COLLECTION);
    static final Nsid DOCUMENT_NSID = Nsid.parse(DOCUMENT_COLLECTION);
    /** Ref name prefixes. */
    static final String POST = "post";
    static final String DOCUMENT = "document";

    private AtprotoRefs() {
    }

    /** The record {@code prefix} names in the stored state, when both its uri and cid are there. */
    static Optional<StrongRef> previous(AdapterRequest req, String prefix) {
        Map<String, String> refs = req.previous().map(NetworkState::refs).orElse(Map.of());
        String uri = refs.get(prefix + ".uri");
        String cid = refs.get(prefix + ".cid");
        if (uri == null || cid == null || !AtUri.isValid(uri) || !Cid.isValid(cid)) {
            return Optional.empty();
        }
        return Optional.of(new StrongRef(AtUri.parse(uri), Cid.parse(cid)));
    }

    static void put(Map<String, String> refs, String prefix, StrongRef ref) {
        refs.put(prefix + ".uri", ref.uri().toString());
        refs.put(prefix + ".cid", ref.cid().value());
    }
}
