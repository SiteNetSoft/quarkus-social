package org.sitenetsoft.quarkus.social.activitypub;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.sitenetsoft.activitystreams.ASObject;
import org.sitenetsoft.activitystreams.Actor;
import org.sitenetsoft.activitystreams.Announce;
import org.sitenetsoft.activitystreams.Create;
import org.sitenetsoft.activitystreams.Follow;
import org.sitenetsoft.activitystreams.Like;
import org.sitenetsoft.activitystreams.ObjectOrLink;
import org.sitenetsoft.activitystreams.Undo;
import org.sitenetsoft.quarkus.activitypub.ActivityPubUrls;
import org.sitenetsoft.quarkus.activitypub.actor.LocalActor;
import org.sitenetsoft.quarkus.activitypub.actor.LocalActors;
import org.sitenetsoft.quarkus.activitypub.inbox.Inbound;
import org.sitenetsoft.quarkus.activitypub.inbox.InboundActivityContext;
import org.sitenetsoft.quarkus.activitypub.store.FollowState;
import org.sitenetsoft.quarkus.activitypub.store.Stores;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.content.Html;
import org.sitenetsoft.quarkus.social.engine.PublishEngine;
import org.sitenetsoft.quarkus.social.events.SocialAuthor;
import org.sitenetsoft.quarkus.social.events.SocialFollow;
import org.sitenetsoft.quarkus.social.events.SocialReaction;
import org.sitenetsoft.quarkus.social.events.SocialReactionUndone;
import org.sitenetsoft.quarkus.social.events.SocialReply;
import org.sitenetsoft.quarkus.social.store.StoredPost;

import io.smallrye.mutiny.Uni;

/**
 * Turns inbound ActivityPub activities into the neutral {@code Social*} events: replies to, likes and reposts of
 * posts published through {@code quarkus-social} (matched by the stored {@code object} ref), and follows of local
 * actors.
 * <p>
 * The observers are synchronous {@code @Inbound} ones: they run on the inbox's virtual thread right after the
 * built-in handler in the same store transaction, so the follower store already holds the outcome a {@code Follow}
 * event reports, and an app's {@code @Observes} of a neutral event sees the events of one sender in order (async
 * observers run concurrently and could deliver an {@code Undo} before its {@code Like}). Blocking on the post lookup
 * is fine on that thread (bounded by {@link #LOOKUP_TIMEOUT}). Nothing is fetched. No match fires nothing; a failure
 * of any step (or of an app observer) is logged and never reaches the inbox.
 */
@ApplicationScoped
public class InboundInteractions {

    static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger LOG = Logger.getLogger(InboundInteractions.class);
    private static final Network NETWORK = Network.ACTIVITYPUB;

    @Inject
    PublishEngine engine;
    @Inject
    Social social;
    @Inject
    ActivityPubSocialAdapter adapter;
    @Inject
    ActivityPubSocialConfig config;
    @Inject
    ActivityPubUrls urls;
    @Inject
    LocalActors actors;
    @Inject
    Stores stores;
    @Inject
    Event<SocialReply> replies;
    @Inject
    Event<SocialReaction> reactions;
    @Inject
    Event<SocialReactionUndone> undone;
    @Inject
    Event<SocialFollow> follows;

    void onCreate(@Observes @Inbound Create create, InboundActivityContext ctx) {
        guarded(create, () -> {
            Optional<ASObject> object = create.embeddedObject()
                    .or(() -> create.objectUri().flatMap(id -> stores.objects().find(id)));
            if (object.isEmpty() || object.get().inReplyTo().isEmpty()) {
                return;
            }
            ASObject reply = object.get();
            for (ObjectOrLink target : reply.inReplyTo()) {
                Optional<StoredPost> post = target.uri().flatMap(this::postOf);
                if (post.isPresent()) {
                    SocialReply event = new SocialReply(post.get().postId(), post.get().account(), NETWORK,
                            author(create, ctx), Html.sanitize(reply.content() == null ? "" : reply.content()),
                            firstUri(reply.url()).or(() -> Optional.ofNullable(reply.id())), Instant.now());
                    fire(() -> replies.fire(event));
                    return;
                }
            }
        });
    }

    void onLike(@Observes @Inbound Like like, InboundActivityContext ctx) {
        reaction(like, ctx, SocialReaction.Type.LIKE);
    }

    void onAnnounce(@Observes @Inbound Announce announce, InboundActivityContext ctx) {
        reaction(announce, ctx, SocialReaction.Type.REPOST);
    }

    private void reaction(org.sitenetsoft.activitystreams.Activity activity, InboundActivityContext ctx,
            SocialReaction.Type type) {
        guarded(activity, () -> activity.objectUri().flatMap(this::postOf).ifPresent(post -> {
            SocialReaction event = new SocialReaction(post.postId(), post.account(), NETWORK, author(activity, ctx),
                    type, Instant.now());
            fire(() -> reactions.fire(event));
        }));
    }

    /**
     * {@code Undo} of a {@code Like}/{@code Announce}/{@code Follow} by the same actor. A {@code Like} or
     * {@code Announce} is only known when embedded (the library stores neither), so an {@code Undo} that merely
     * references one fires nothing: resolving it would take a fetch.
     */
    void onUndo(@Observes @Inbound Undo undo, InboundActivityContext ctx) {
        guarded(undo, () -> {
            Optional<ASObject> object = undo.embeddedObject()
                    .or(() -> undo.objectUri().flatMap(id -> stores.objects().find(id)));
            if (object.isEmpty() || !(object.get() instanceof org.sitenetsoft.activitystreams.Activity undoneActivity)
                    || !undoneActivity.actorUri().filter(ctx.signerActor()::equals).isPresent()) {
                return;
            }
            if (undoneActivity instanceof Follow follow) {
                localActor(follow).ifPresent(local -> account(local).ifPresent(account -> {
                    SocialFollow event = new SocialFollow(account, NETWORK, author(undo, ctx),
                            SocialFollow.State.UNDONE);
                    fire(() -> follows.fire(event));
                }));
                return;
            }
            SocialReaction.Type type = undoneActivity instanceof Like ? SocialReaction.Type.LIKE
                    : undoneActivity instanceof Announce ? SocialReaction.Type.REPOST : null;
            if (type == null) {
                return;
            }
            undoneActivity.objectUri().flatMap(this::postOf).ifPresent(post -> {
                SocialReactionUndone event = new SocialReactionUndone(post.postId(), post.account(), NETWORK,
                        author(undo, ctx), type, Instant.now());
                fire(() -> undone.fire(event));
            });
        });
    }

    /** After {@code FollowHandler}: the state is what the follower store holds now ({@code REJECTED} when nothing). */
    void onFollow(@Observes @Inbound Follow follow, InboundActivityContext ctx) {
        guarded(follow, () -> localActor(follow).ifPresent(local -> account(local).ifPresent(account -> {
            SocialFollow.State state = stores.followers().follower(local.id(), ctx.signerActor())
                    .map(relation -> state(relation.state())).orElse(SocialFollow.State.REJECTED);
            SocialFollow event = new SocialFollow(account, NETWORK, author(follow, ctx), state);
            fire(() -> follows.fire(event));
        })));
    }

    // ---- helpers ----

    private static SocialFollow.State state(FollowState state) {
        return switch (state) {
            case ACCEPTED -> SocialFollow.State.ACCEPTED;
            case PENDING -> SocialFollow.State.PENDING;
            case REJECTED -> SocialFollow.State.REJECTED;
        };
    }

    private Optional<LocalActor> localActor(Follow follow) {
        return follow.objectUri().flatMap(urls::usernameOf).flatMap(actors::find);
    }

    /** The app account of a local actor: the app's {@code SocialAccountLinks}, else the adapter's default (R1). */
    private Optional<String> account(LocalActor local) {
        Optional<String> account = adapter.accountFor(local.username()).await().atMost(LOOKUP_TIMEOUT);
        return account == null ? Optional.empty() : account;
    }

    /** The post published with {@code ref} as the id of its ActivityPub object, with its account. */
    private Optional<StoredPost> postOf(URI ref) {
        Uni<Optional<String>> id = engine.findPostIdByRef(NETWORK, ref.toString());
        return id.await().atMost(LOOKUP_TIMEOUT).flatMap(social::status);
    }

    /** The actor of {@code activity} as far as it is embedded; otherwise only its id (the signer). */
    private SocialAuthor author(org.sitenetsoft.activitystreams.Activity activity, InboundActivityContext ctx) {
        URI id = activity.actorUri().orElse(ctx.signerActor());
        Optional<Actor> actor = Optional.ofNullable(activity.actor()).flatMap(ObjectOrLink::object)
                .filter(Actor.class::isInstance).map(Actor.class::cast);
        if (actor.isEmpty()) {
            return SocialAuthor.of(NETWORK, id.toString());
        }
        Actor a = actor.get();
        Optional<String> handle = Optional.ofNullable(a.preferredUsername()).filter(n -> !n.isBlank())
                .flatMap(n -> Optional.ofNullable(id.getHost()).map(h -> n + "@" + h));
        Optional<URI> avatar = a.icon().stream().map(icon -> icon.uri()
                .or(() -> icon.object().flatMap(o -> firstUri(o.url())))).flatMap(Optional::stream).findFirst();
        return new SocialAuthor(NETWORK, id.toString(), handle,
                Optional.ofNullable(a.name()).filter(n -> !n.isBlank()),
                firstUri(a.url()).or(() -> Optional.of(id)), avatar);
    }

    private static Optional<URI> firstUri(java.util.List<ObjectOrLink> links) {
        return links.stream().map(l -> l.uri().or(() -> l.object().map(ASObject::id))).flatMap(Optional::stream)
                .findFirst();
    }

    /** Runs {@code step}; nothing it throws reaches the inbox. */
    private void guarded(org.sitenetsoft.activitystreams.Activity activity, Runnable step) {
        if (!config.enabled()) {
            return;
        }
        try {
            step.run();
        } catch (RuntimeException e) {
            LOG.warnf(e, "No social event for inbound %s %s", activity.getClass().getSimpleName(), activity.id());
        }
    }

    /** An application observer's failure is contained: it must not stop the inbox or the next event. */
    private void fire(Runnable fire) {
        try {
            fire.run();
        } catch (RuntimeException e) {
            LOG.warn("A social event observer failed", e);
        }
    }
}
