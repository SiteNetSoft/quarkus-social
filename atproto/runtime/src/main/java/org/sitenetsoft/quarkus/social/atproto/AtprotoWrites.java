package org.sitenetsoft.quarkus.social.atproto;

import static org.sitenetsoft.quarkus.social.atproto.AtprotoRefs.DOCUMENT;
import static org.sitenetsoft.quarkus.social.atproto.AtprotoRefs.DOCUMENT_NSID;
import static org.sitenetsoft.quarkus.social.atproto.AtprotoRefs.POST;
import static org.sitenetsoft.quarkus.social.atproto.AtprotoRefs.POST_NSID;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.atproto.syntax.RecordKey;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.quarkus.atproto.xrpc.AtprotoRecords;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordResult;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialConfig.EditPost;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialConfig.Output;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;

import com.atproto.repo.StrongRef;

/**
 * The record writes of publish, update and delete, blocking (worker thread). Each method adds the refs of what exists
 * on the network to {@code refs} as it goes, so a call that fails half-way reports what it wrote; checks that can
 * refuse the post ({@link BlueskyPosts.Refused}) run before anything is written.
 */
@ApplicationScoped
public class AtprotoWrites {

    static final String POST_NOT_EDITED = "Bluesky post not edited";
    /** The fields that are fixed for a post's document: where it lives and when it was published. */
    private static final List<String> DOCUMENT_IDENTITY = List.of("/site", "/path", "/publishedAt");

    @Inject
    AtprotoRepo repo;
    @Inject
    AtprotoSocialConfig config;
    @Inject
    StandardSitePublications publications;

    private ContentWarningSetting cw;

    @PostConstruct
    void init() {
        cw = ContentWarningSetting.parse(config.contentWarning());
    }

    /**
     * The post, then (ARTICLE) the document pointing at it, each at a key derived from the post, skipping what
     * {@code previous} lists: a retry writes only the missing part.
     */
    NetworkResult publish(AdapterRequest req, Map<String, String> refs) {
        Post post = req.post();
        boolean wantPost = config.output() != Output.DOCUMENT;
        boolean wantDocument = post.kind() == Kind.ARTICLE && config.output() != Output.POST;
        Optional<StrongRef> postRef = AtprotoRefs.previous(req, POST);
        app.bsky.feed.Post bsky = wantPost && postRef.isEmpty() ? BlueskyPosts.build(req, cw) : null;
        Did did = Did.parse(req.identity());
        AtprotoRecords records = repo.records(did);
        boolean sent = false;
        if (wantPost) {
            if (postRef.isEmpty()) {
                AtprotoRepo.Written w = repo.createOrFind(records, did, POST_NSID,
                        RecordKeys.of(post.id(), POST_NSID, post.publishedAt()), bsky, postIdentity(post));
                sent |= w.created();
                postRef = Optional.of(w.ref());
            }
            AtprotoRefs.put(refs, POST, postRef.get());
        }
        if (wantDocument) {
            Optional<StrongRef> docRef = AtprotoRefs.previous(req, DOCUMENT);
            if (docRef.isEmpty()) {
                URI site = StandardSiteDocuments.site(publications, req);
                AtprotoRepo.Written w = repo.createOrFind(records, did, DOCUMENT_NSID,
                        RecordKeys.of(post.id(), DOCUMENT_NSID, post.publishedAt()),
                        StandardSiteDocuments.build(req, cw, site, postRef, Optional.empty()), DOCUMENT_IDENTITY);
                sent |= w.created();
                docRef = Optional.of(w.ref());
            }
            AtprotoRefs.put(refs, DOCUMENT, docRef.get());
        }
        return published(postRef, refs, sent ? Optional.empty() : Optional.of("already written by an earlier attempt"),
                sent);
    }

    /**
     * The document is replaced ({@code put}, swapping on its stored CID); the post is kept, or with
     * {@code edit-post=recreate} deleted and created again under a new key (the document then points at the new one).
     */
    NetworkResult update(AdapterRequest req, Map<String, String> refs) {
        Post post = req.post();
        Optional<StrongRef> postRef = AtprotoRefs.previous(req, POST);
        Optional<StrongRef> docRef = AtprotoRefs.previous(req, DOCUMENT);
        boolean recreate = postRef.isPresent() && config.editPost() == EditPost.RECREATE;
        app.bsky.feed.Post bsky = recreate ? BlueskyPosts.build(req, cw) : null;
        Did did = Did.parse(req.identity());
        AtprotoRecords records = repo.records(did);
        boolean sent = false;
        if (recreate) {
            // the new key is derived from the old one: a retried edit (the document put failed) finds the post it
            // already created instead of adding a second one
            RecordKey old = postRef.get().uri().rkey().orElseThrow();
            records.delete(POST_NSID, old);
            AtprotoRepo.Written w = repo.createOrFind(records, did, POST_NSID,
                    RecordKeys.replacing(post.id(), POST_NSID, old, post.publishedAt()), bsky, postIdentity(post));
            postRef = Optional.of(w.ref());
            AtprotoRefs.put(refs, POST, postRef.get());
            sent = true;
        }
        if (docRef.isPresent()) {
            AtUri uri = docRef.get().uri();
            RecordResult r = records.put(DOCUMENT_NSID, uri.rkey().orElseThrow(),
                    StandardSiteDocuments.build(req, cw, StandardSiteDocuments.site(publications, req), postRef,
                            Optional.of(Instant.now())),
                    Optional.of(docRef.get().cid()));
            AtprotoRefs.put(refs, DOCUMENT, new StrongRef(r.uri(), r.cid()));
            sent = true;
        }
        Optional<String> note = postRef.isPresent() && !recreate ? Optional.of(POST_NOT_EDITED) : Optional.empty();
        return published(postRef, refs, note, sent);
    }

    /** Deletes the post and the document, whichever the stored refs name (deleting a missing record succeeds). */
    NetworkResult delete(AdapterRequest req) {
        Optional<StrongRef> postRef = AtprotoRefs.previous(req, POST);
        Optional<StrongRef> docRef = AtprotoRefs.previous(req, DOCUMENT);
        AtprotoRecords records = repo.records(Did.parse(req.identity()));
        if (docRef.isPresent()) {
            records.delete(DOCUMENT_NSID, docRef.get().uri().rkey().orElseThrow());
        }
        if (postRef.isPresent()) {
            records.delete(POST_NSID, postRef.get().uri().rkey().orElseThrow());
        }
        return new NetworkResult.Published(Network.ATPROTO, Optional.empty(), Map.of(), Optional.empty(), true);
    }

    /**
     * The fields that are fixed for a post's Bluesky record (a record at a derived key with other values there belongs
     * to another post): {@code createdAt}, and the card's URL (ARTICLE) or the text (NOTE).
     */
    private static List<String> postIdentity(Post post) {
        return List.of("/createdAt", post.kind() == Kind.ARTICLE ? "/embed/external/uri" : "/text");
    }

    /** The web link of the post (none for a document-only output; the {@code at://} URIs are in the refs). */
    private NetworkResult.Published published(Optional<StrongRef> postRef, Map<String, String> refs,
            Optional<String> note, boolean sent) {
        Optional<URI> url = postRef.map(StrongRef::uri).map(uri -> URI.create(config.postUrlTemplate()
                .replace("{did}", uri.authority().toString())
                .replace("{rkey}", uri.rkey().orElseThrow().value())));
        return new NetworkResult.Published(Network.ATPROTO, url, refs, note, sent);
    }
}
