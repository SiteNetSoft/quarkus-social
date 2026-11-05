package org.sitenetsoft.quarkus.social.activitypub;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.OptionalInt;
import java.util.function.Supplier;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.sitenetsoft.activitystreams.ASObject;
import org.sitenetsoft.activitystreams.Activity;
import org.sitenetsoft.activitystreams.Create;
import org.sitenetsoft.activitystreams.ObjectOrLink;
import org.sitenetsoft.activitystreams.Tombstone;
import org.sitenetsoft.quarkus.activitypub.ActivityPub;
import org.sitenetsoft.quarkus.activitypub.ActivityPubUrls;
import org.sitenetsoft.quarkus.activitypub.actor.LocalActor;
import org.sitenetsoft.quarkus.activitypub.actor.LocalActors;
import org.sitenetsoft.quarkus.activitypub.outbound.DeliveryEvents.DeliveriesSubmitted;
import org.sitenetsoft.quarkus.activitypub.store.DeliveryState;
import org.sitenetsoft.quarkus.activitypub.store.Stores;
import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.FailureCode;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.SkipReason;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.spi.AdapterRequest;
import org.sitenetsoft.quarkus.social.spi.ReactiveSocialAccountLinks;
import org.sitenetsoft.quarkus.social.spi.SocialAccountLinks;
import org.sitenetsoft.quarkus.social.spi.SocialAdapter;
import org.sitenetsoft.quarkus.social.store.NetworkState;

import io.quarkus.arc.InjectableInstance;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * The ActivityPub network of {@code quarkus-social}: posts become {@code Create}/{@code Update}/{@code Delete}
 * activities of the local actor the account is linked to, published with {@code quarkus-activitypub}'s blocking
 * {@link ActivityPub} on a worker thread (it joins a caller transaction; the engine calls without one).
 * <p>
 * Refs: {@code object} (the object id, also the {@code Published} url), {@code activity} (the {@code Create}),
 * {@code activity.update.<n>} per edit, {@code activity.delete}. {@code Published} means accepted and queued for
 * delivery; {@link DeliveryTracker} reports the delivery progress. The object and {@code Create} ids are derived from
 * the post id, so a retry after a lost answer finds the stored object and reports it instead of publishing again.
 * <p>
 * An {@link IllegalArgumentException} from the protocol (for example an object not owned by the actor) is
 * {@code Failed(REJECTED)}, not retryable; any other exception is {@code Failed(UNAVAILABLE)}, retryable.
 */
@ApplicationScoped
public class ActivityPubSocialAdapter implements SocialAdapter {

    static final String OBJECT = "object";
    static final String ACTIVITY = "activity";
    static final String UPDATE_PREFIX = "activity.update.";
    static final String DELETE = "activity.delete";

    private static final Logger LOG = Logger.getLogger(ActivityPubSocialAdapter.class);
    private static final Network NETWORK = Network.ACTIVITYPUB;
    private static final Capabilities CAPABILITIES = new Capabilities(
            EnumSet.of(Visibility.PUBLIC, Visibility.UNLISTED, Visibility.FOLLOWERS),
            EnumSet.of(Kind.NOTE, Kind.ARTICLE), true, true, true, OptionalInt.empty(), true, true);

    @Inject
    ActivityPub activityPub;
    @Inject
    ActivityPubUrls urls;
    @Inject
    LocalActors actors;
    @Inject
    Stores stores;
    @Inject
    DeliveryTracker tracker;
    @Inject
    ActivityPubSocialConfig config;
    @Inject
    DefaultActivityPubAccountLinks defaultLinks;
    @Inject
    InjectableInstance<SocialAccountLinks> appLinks;
    @Inject
    InjectableInstance<ReactiveSocialAccountLinks> reactiveAppLinks;

    /** Post ids whose publish is running in this process (an engine timeout does not stop the blocking call). */
    private final Set<String> publishing = ConcurrentHashMap.newKeySet();
    private PostMapper mapper;

    @PostConstruct
    void init() {
        mapper = new PostMapper(config.articleStyle());
    }

    @Override
    public Network network() {
        return NETWORK;
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    /**
     * The app's {@code ReactiveSocialAccountLinks} or {@code SocialAccountLinks} when it provides one, else
     * {@link DefaultActivityPubAccountLinks}. Disabled by config: every account is "linked" so the calls answer
     * {@code Skipped(DISABLED_BY_CONFIG)} rather than {@code NOT_LINKED}.
     */
    @Override
    public Uni<Optional<String>> linkedIdentity(String account) {
        if (!config.enabled()) {
            return Uni.createFrom().item(Optional.of(account));
        }
        if (reactiveAppLinks.isResolvable()) {
            return reactiveAppLinks.get().activityPubUsername(account);
        }
        if (appLinks.isResolvable()) {
            return blocking(() -> appLinks.get().activityPubUsername(account));
        }
        return blocking(() -> defaultLinks.activityPubUsername(account));
    }

    /** The app account a local username stands for (the reverse of {@link #linkedIdentity}). */
    public Uni<Optional<String>> accountFor(String username) {
        if (reactiveAppLinks.isResolvable()) {
            return reactiveAppLinks.get().accountForActivityPubUsername(username);
        }
        if (appLinks.isResolvable()) {
            return blocking(() -> appLinks.get().accountForActivityPubUsername(username));
        }
        return Uni.createFrom().item(defaultLinks.accountForActivityPubUsername(username));
    }

    @Override
    public Uni<NetworkResult> publish(AdapterRequest req) {
        return call(req, () -> doPublish(req));
    }

    @Override
    public Uni<NetworkResult> update(AdapterRequest req) {
        return call(req, () -> doUpdate(req));
    }

    @Override
    public Uni<NetworkResult> delete(AdapterRequest req) {
        return call(req, () -> doDelete(req));
    }

    /**
     * At most one publish per post id at a time in this process: a call the engine timed out may still be running
     * (recipient lookups happen outside the transaction), and the stored-object check below would not see its object
     * yet, so a retry meanwhile is answered {@code IN_PROGRESS} without calling ActivityPub.
     */
    private NetworkResult doPublish(AdapterRequest req) {
        String postId = req.post().id();
        if (!publishing.add(postId)) {
            return new NetworkResult.Failed(NETWORK, FailureCode.IN_PROGRESS,
                    "a previous publish of this post is still running", true, Map.of());
        }
        try {
            return publishOnce(req);
        } finally {
            publishing.remove(postId);
        }
    }

    private NetworkResult publishOnce(AdapterRequest req) {
        LocalActor actor = actor(req.identity());
        String postId = req.post().id();
        URI objectId = PostMapper.objectId(urls, postId);
        URI createId = PostMapper.createId(urls, postId);
        Map<String, String> refs = Map.of(OBJECT, objectId.toString(), ACTIVITY, createId.toString());
        Optional<ASObject> stored = stores.objects().find(objectId);
        if (stored.isPresent()) {
            // an earlier call got this far and its answer was lost (timeout, crash before the state was saved)
            if (stored.get() instanceof Tombstone) {
                throw new IllegalArgumentException(objectId + " was deleted; publish the post under a new id");
            }
            LOG.debugf("post %s is already stored as %s; not publishing it again", postId, objectId);
            return published(objectId, refs, "already queued by an earlier attempt", false);
        }
        ASObject object = mapper.toObject(actor, req, urls);
        Create create = new Create();
        create.id(createId);
        create.actor(ObjectOrLink.of(actor.id())).object(ObjectOrLink.of(object));
        create.to(object.to().toArray(ObjectOrLink[]::new));
        create.cc(object.cc().toArray(ObjectOrLink[]::new));
        try (DeliveryTracker.Call call = tracker.begin(postId, createId)) {
            activityPub.publish(actor, create);
            call.succeeded();
            return published(objectId, refs, note(call.submitted()), true);
        }
    }

    private NetworkResult doUpdate(AdapterRequest req) {
        Optional<URI> objectId = objectRef(req);
        if (objectId.isEmpty()) {
            return noObject(req);
        }
        LocalActor actor = actor(req.identity());
        ASObject object = mapper.toObject(actor, req, urls);
        object.id(objectId.get());
        // an edit keeps the original publication time
        stores.objects().find(objectId.get()).map(ASObject::published).ifPresent(object::published);
        String ref = UPDATE_PREFIX + (lastUpdate(req) + 1);
        try (DeliveryTracker.Call call = tracker.begin(req.post().id(), null)) {
            Activity update = activityPub.update(actor, object);
            call.succeeded();
            return published(objectId.get(), Map.of(ref, update.id().toString()), note(call.submitted()), true);
        }
    }

    private NetworkResult doDelete(AdapterRequest req) {
        Optional<URI> objectId = objectRef(req);
        if (objectId.isEmpty()) {
            return noObject(req);
        }
        Optional<ASObject> stored = stores.objects().find(objectId.get());
        if (stored.isEmpty() || stored.get() instanceof Tombstone) {
            return published(objectId.get(), Map.of(), "already deleted", false);
        }
        LocalActor actor = actor(req.identity());
        try (DeliveryTracker.Call call = tracker.begin(req.post().id(), null)) {
            Activity delete = activityPub.delete(actor, objectId.get());
            call.succeeded();
            return published(objectId.get(), Map.of(DELETE, delete.id().toString()), note(call.submitted()),
                    true);
        }
    }

    /** Runs {@code work} on a worker thread, mapping exceptions to results. */
    private Uni<NetworkResult> call(AdapterRequest req, Supplier<NetworkResult> work) {
        if (!config.enabled()) {
            return Uni.createFrom().item(new NetworkResult.Skipped(NETWORK, SkipReason.DISABLED_BY_CONFIG,
                    "quarkus.social.activitypub.enabled is false"));
        }
        return blocking(() -> {
            try {
                return work.get();
            } catch (IllegalArgumentException e) {
                LOG.debugf(e, "post %s refused by ActivityPub", req.post().id());
                return new NetworkResult.Failed(NETWORK, FailureCode.REJECTED, message(e), false, Map.of());
            } catch (RuntimeException e) {
                LOG.warnf(e, "post %s: ActivityPub publishing failed", req.post().id());
                return new NetworkResult.Failed(NETWORK, FailureCode.UNAVAILABLE, message(e), true, Map.of());
            }
        });
    }

    private LocalActor actor(String username) {
        return actors.find(username)
                .orElseThrow(() -> new IllegalArgumentException("no local ActivityPub actor '" + username + "'"));
    }

    private static Optional<URI> objectRef(AdapterRequest req) {
        return req.previous().map(NetworkState::refs).map(refs -> refs.get(OBJECT)).map(URI::create);
    }

    private static NetworkResult noObject(AdapterRequest req) {
        return new NetworkResult.Failed(NETWORK, FailureCode.REJECTED,
                "no ActivityPub object is recorded for post " + req.post().id(), false, Map.of());
    }

    /** The highest {@code n} of the {@code activity.update.<n>} refs, 0 when the post was never updated. */
    private static int lastUpdate(AdapterRequest req) {
        Map<String, String> refs = req.previous().map(NetworkState::refs).orElse(Map.of());
        int last = 0;
        for (String key : refs.keySet()) {
            if (key.startsWith(UPDATE_PREFIX)) {
                try {
                    last = Math.max(last, Integer.parseInt(key.substring(UPDATE_PREFIX.length())));
                } catch (NumberFormatException e) {
                    // not one of ours
                }
            }
        }
        return last;
    }

    /** {@code queued for N inbox(es), M failed at once}: pending deliveries, and those failed or given up at submit. */
    private static String note(Optional<DeliveriesSubmitted> submitted) {
        List<DeliveryState> states = submitted.map(s -> s.deliveries().stream().map(d -> d.state()).toList())
                .orElse(List.of());
        long queued = states.stream().filter(s -> s == DeliveryState.PENDING).count();
        return "queued for " + queued + (queued == 1 ? " inbox, " : " inboxes, ") + (states.size() - queued)
                + " failed at once";
    }

    /** @param sent whether this call sent anything */
    private static NetworkResult.Published published(URI url, Map<String, String> refs, String note, boolean sent) {
        return new NetworkResult.Published(NETWORK, Optional.of(url), refs, Optional.of(note), sent);
    }

    private static String message(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }

    private static <T> Uni<T> blocking(Supplier<T> work) {
        return Uni.createFrom().item(work).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
