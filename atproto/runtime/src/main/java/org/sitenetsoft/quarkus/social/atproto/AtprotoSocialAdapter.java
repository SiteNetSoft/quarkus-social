package org.sitenetsoft.quarkus.social.atproto;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessionExpired;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoTemporarilyUnavailable;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.auth.AtprotoScopeException;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordValidationException;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcException;
import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.atproto.AtprotoSocialConfig.Output;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;
import org.sitenetsoft.quarkus.social.spi.ReactiveSocialAccountLinks;
import org.sitenetsoft.quarkus.social.spi.SocialAccountLinks;
import org.sitenetsoft.quarkus.social.spi.SocialAdapter;

import io.quarkus.arc.InjectableInstance;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * The AT Protocol network of {@code quarkus-social}: a post becomes an {@code app.bsky.feed.post} and, for an
 * ARTICLE, a {@code site.standard.document} pointing at it ({@code quarkus.social.atproto.output}), written to the
 * repository of the DID the app account linked ({@code quarkus.atproto.oauth.mode=link}) with the blocking records API
 * on a worker thread ({@link AtprotoWrites}).
 * <p>
 * Refs: {@code post.uri}, {@code post.cid}, {@code document.uri}, {@code document.cid}. Record keys are derived from
 * the post ({@link RecordKeys}), so a retry finds what an earlier attempt wrote. Errors: an expired session is
 * {@code SESSION_EXPIRED}, a scope the session lacks {@code SCOPE_MISSING}, a temporary outage, a 5xx or 429
 * {@code UNAVAILABLE} (retryable), any other PDS refusal or an invalid record {@code REJECTED}.
 */
@ApplicationScoped
public class AtprotoSocialAdapter implements SocialAdapter {

    static final String LINK_MODE_REQUIRED = "quarkus.atproto.oauth.mode must be link";
    static final String SIGN_IN_AGAIN = "sign in to Bluesky again";

    private static final Logger LOG = Logger.getLogger(AtprotoSocialAdapter.class);
    private static final Network NETWORK = Network.ATPROTO;

    @Inject
    AtprotoSocialConfig config;
    @Inject
    OAuthConfig oauth;
    @Inject
    AtprotoWrites writes;
    @Inject
    DefaultAtprotoAccountLinks defaultLinks;
    @Inject
    InjectableInstance<SocialAccountLinks> appLinks;
    @Inject
    InjectableInstance<ReactiveSocialAccountLinks> reactiveAppLinks;

    /** Post ids whose publish is running in this process (an engine timeout does not stop the blocking call). */
    private final Set<String> publishing = ConcurrentHashMap.newKeySet();

    /** Fails startup on an invalid content-warning setting; logs an error in {@code login} mode. */
    void checkSettings(@Observes StartupEvent event) {
        ContentWarningSetting.parse(config.contentWarning());
        if (config.enabled() && oauth.mode() != OAuthConfig.Mode.LINK) {
            LOG.error("quarkus-social can't publish to Bluesky: " + LINK_MODE_REQUIRED + " (login mode has no "
                    + "application accounts to link to); every post is Skipped(NOT_LINKED) there");
        }
    }

    @Override
    public Network network() {
        return NETWORK;
    }

    @Override
    public Capabilities capabilities() {
        Set<Kind> kinds = config.output() == Output.DOCUMENT ? EnumSet.of(Kind.ARTICLE)
                : EnumSet.of(Kind.NOTE, Kind.ARTICLE);
        return new Capabilities(EnumSet.of(Visibility.PUBLIC), kinds, true, true, false,
                OptionalInt.of(BlueskyPosts.MAX_GRAPHEMES), false, false);
    }

    @Override
    public Optional<String> unavailableReason() {
        return config.enabled() && oauth.mode() != OAuthConfig.Mode.LINK ? Optional.of(LINK_MODE_REQUIRED)
                : Optional.empty();
    }

    /**
     * The app's {@code ReactiveSocialAccountLinks} or {@code SocialAccountLinks} when it provides one, else
     * {@link DefaultAtprotoAccountLinks}. Disabled by config: every account is "linked" so the calls answer
     * {@code Skipped(DISABLED_BY_CONFIG)} rather than {@code NOT_LINKED}.
     */
    @Override
    public Uni<Optional<String>> linkedIdentity(String account) {
        if (!config.enabled()) {
            return Uni.createFrom().item(Optional.of(account));
        }
        if (reactiveAppLinks.isResolvable()) {
            return reactiveAppLinks.get().atprotoDid(account);
        }
        if (appLinks.isResolvable()) {
            return blocking(() -> appLinks.get().atprotoDid(account));
        }
        return defaultLinks.atprotoDid(account);
    }

    /** The app account a DID stands for: only an app's {@code SocialAccountLinks} knows. */
    public Uni<Optional<String>> accountFor(String did) {
        if (reactiveAppLinks.isResolvable()) {
            return reactiveAppLinks.get().accountForAtprotoDid(did);
        }
        if (appLinks.isResolvable()) {
            return blocking(() -> appLinks.get().accountForAtprotoDid(did));
        }
        return Uni.createFrom().item(defaultLinks.accountForAtprotoDid(did));
    }

    @Override
    public Uni<NetworkResult> publish(AdapterRequest req) {
        return call(req, refs -> {
            Optional<NetworkResult> refused = refusal(req, false);
            return refused.isPresent() ? refused.get() : publishOnce(req, refs);
        });
    }

    @Override
    public Uni<NetworkResult> update(AdapterRequest req) {
        return call(req, refs -> refusal(req, true).orElseGet(() -> writes.update(req, refs)));
    }

    @Override
    public Uni<NetworkResult> delete(AdapterRequest req) {
        return call(req, refs -> writes.delete(req));
    }

    /**
     * At most one publish per post id at a time in this process: a call the engine timed out may still be writing,
     * and a retry meanwhile could create the document before the post's ref is known, so it is answered
     * {@code IN_PROGRESS} without writing.
     */
    private NetworkResult publishOnce(AdapterRequest req, Map<String, String> refs) {
        String postId = req.post().id();
        if (!publishing.add(postId)) {
            return new NetworkResult.Failed(NETWORK, FailureCode.IN_PROGRESS,
                    "a previous publish of this post is still running", true, Map.of());
        }
        try {
            return writes.publish(req, refs);
        } finally {
            publishing.remove(postId);
        }
    }

    /**
     * What Bluesky can't carry: a non-public post, a NOTE for a document-only output, a content warning with
     * {@code content-warning=skip} (unless the post names its own self-labels). On an edit the post is already
     * there, so a warning added now is a failure that leaves the Bluesky copy unchanged.
     */
    private Optional<NetworkResult> refusal(AdapterRequest req, boolean edit) {
        var post = req.post();
        if (!edit && post.visibility() != Visibility.PUBLIC) {
            return Optional.of(new NetworkResult.Skipped(NETWORK, SkipReason.VISIBILITY_NOT_SUPPORTED,
                    "Bluesky posts are public; this one is " + post.visibility()));
        }
        if (!edit && post.kind() == Kind.NOTE && config.output() == Output.DOCUMENT) {
            return Optional.of(new NetworkResult.Skipped(NETWORK, SkipReason.KIND_NOT_SUPPORTED,
                    "quarkus.social.atproto.output=document publishes only ARTICLE posts"));
        }
        boolean skip = BlueskyPosts.warned(post) && post.atprotoLabels().isEmpty()
                && ContentWarningSetting.parse(config.contentWarning()).mode() == ContentWarningSetting.Mode.SKIP;
        if (skip && !edit) {
            return Optional.of(new NetworkResult.Skipped(NETWORK, SkipReason.CONTENT_WARNING_NOT_SUPPORTED,
                    "Bluesky has no free-text content warning (quarkus.social.atproto.content-warning=skip)"));
        }
        if (skip) {
            return Optional.of(new NetworkResult.Failed(NETWORK, FailureCode.CONTENT_WARNING_NOT_SUPPORTED,
                    "the post now has a content warning, which Bluesky can't show "
                            + "(quarkus.social.atproto.content-warning=skip); the existing Bluesky copy is unchanged",
                    false, Map.of()));
        }
        return Optional.empty();
    }

    /** Runs {@code work} on a worker thread, mapping exceptions to results with the refs written so far. */
    private Uni<NetworkResult> call(AdapterRequest req, Function<Map<String, String>, NetworkResult> work) {
        if (!config.enabled()) {
            return Uni.createFrom().item(new NetworkResult.Skipped(NETWORK, SkipReason.DISABLED_BY_CONFIG,
                    "quarkus.social.atproto.enabled is false"));
        }
        return blocking(() -> {
            Map<String, String> refs = new LinkedHashMap<>();
            try {
                return work.apply(refs);
            } catch (RuntimeException e) {
                return failure(req, e, refs);
            }
        });
    }

    private static NetworkResult failure(AdapterRequest req, RuntimeException e, Map<String, String> refs) {
        String postId = req.post().id();
        if (e instanceof BlueskyPosts.Refused refused) {
            return refused.result();
        }
        if (e instanceof AtprotoSessionExpired) {
            LOG.debugf(e, "post %s: the Bluesky session of %s has expired", postId, req.identity());
            return failed(FailureCode.SESSION_EXPIRED, SIGN_IN_AGAIN, false, refs);
        }
        if (e instanceof AtprotoScopeException) {
            return failed(FailureCode.SCOPE_MISSING, message(e), false, refs);
        }
        if (e instanceof AtprotoTemporarilyUnavailable) {
            return failed(FailureCode.UNAVAILABLE, message(e), true, refs);
        }
        if (e instanceof XrpcException x) {
            if (x.status() == 429 || x.status() >= 500) {
                return failed(FailureCode.UNAVAILABLE, message(e), true, refs);
            }
            if (x.status() == 403 && "InsufficientScope".equals(x.error())) {
                return failed(FailureCode.SCOPE_MISSING, message(e), false, refs);
            }
            return failed(FailureCode.REJECTED, message(e), false, refs);
        }
        if (e instanceof RecordValidationException || e instanceof IllegalArgumentException) {
            return failed(FailureCode.REJECTED, message(e), false, refs);
        }
        LOG.warnf(e, "post %s: Bluesky publishing failed", postId);
        return failed(FailureCode.UNAVAILABLE, message(e), true, refs);
    }

    private static NetworkResult.Failed failed(FailureCode code, String reason, boolean retryable,
            Map<String, String> refs) {
        return new NetworkResult.Failed(NETWORK, code, reason, retryable, refs);
    }

    private static String message(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }

    private static <T> Uni<T> blocking(Supplier<T> work) {
        return Uni.createFrom().item(work).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
