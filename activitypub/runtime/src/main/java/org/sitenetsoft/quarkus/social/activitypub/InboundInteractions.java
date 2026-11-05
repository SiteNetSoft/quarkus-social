package org.sitenetsoft.quarkus.social.activitypub;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.sitenetsoft.activitystreams.ASObject;
import org.sitenetsoft.activitystreams.Activity;
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
import org.sitenetsoft.quarkus.social.events.ReplyAudience;
import org.sitenetsoft.quarkus.social.events.SocialAuthor;
import org.sitenetsoft.quarkus.social.events.SocialFollow;
import org.sitenetsoft.quarkus.social.events.SocialReaction;
import org.sitenetsoft.quarkus.social.events.SocialReactionUndone;
import org.sitenetsoft.quarkus.social.events.SocialReply;
import org.sitenetsoft.quarkus.social.store.StoredPost;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * Turns inbound ActivityPub activities into the neutral {@code Social*} events: replies to, likes and reposts of
 * posts published through {@code quarkus-social} (matched by the stored {@code object} ref), and follows of local
 * actors.
 * <p>
 * The observers are synchronous {@code @Inbound} ones: they run on the inbox's virtual thread right after the
 * built-in handler in the same store transaction, so the follower store already holds the outcome a {@code Follow}
 * event reports, and one sender's events are computed in order. The events are <em>computed</em> there but
 * <em>fired</em> after the inbox transaction commits (immediately when there is none; never on rollback), so an
 * application observer that fails, or is {@code @Transactional}, cannot roll the inbox's work back.
 * <p>
 * Lookups block (on a virtual thread) but are bounded: at most {@link #MAX_REPLY_TARGETS} {@code inReplyTo} entries
 * and {@link #LOOKUP_BUDGET} in total per activity; on the first timeout the rest is skipped. Nothing is fetched. No
 * match fires nothing; a failure of any step, or of an app observer, is logged and never reaches the inbox.
 * <p>
 * A reply is only reported when it passes the rule {@code CreateUpdateHandler} applies before keeping an object: the
 * object's id is on the signer's host and its {@code attributedTo} is the signer alone; its {@code url} is kept only
 * on the signer's host.
 */
@ApplicationScoped
public class InboundInteractions {

    static final int MAX_REPLY_TARGETS = 5;
    static final Duration LOOKUP_BUDGET = Duration.ofSeconds(2);

    private static final Logger LOG = Logger.getLogger(InboundInteractions.class);
    private static final Network NETWORK = Network.ACTIVITYPUB;
    private static final List<String> PUBLIC = List.of("https://www.w3.org/ns/activitystreams#Public", "as:Public",
            "Public");
    private static final boolean JTA = jtaPresent();

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

    /** The time left for the lookups of one inbound activity. */
    private static final class Budget {
        private final long end = System.nanoTime() + LOOKUP_BUDGET.toNanos();

        Duration remaining() {
            long left = end - System.nanoTime();
            if (left <= 0) {
                throw new LookupTimeout();
            }
            return Duration.ofNanos(left);
        }

        <T> T await(Uni<T> uni) {
            try {
                return uni.await().atMost(remaining());
            } catch (io.smallrye.mutiny.TimeoutException e) {
                throw new LookupTimeout();
            }
        }
    }

    private static final class LookupTimeout extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LookupTimeout() {
            super(null, null, false, false);
        }
    }

    void onCreate(@Observes @Inbound Create create, InboundActivityContext ctx) {
        guarded(create, budget -> {
            Optional<ASObject> object = create.embeddedObject()
                    .or(() -> create.objectUri().flatMap(id -> stores.objects().find(id)));
            if (object.isEmpty() || object.get().inReplyTo().isEmpty()) {
                return;
            }
            ASObject reply = object.get();
            URI signer = ctx.signerActor();
            if (!accepted(reply, signer)) {
                LOG.debugf("Reply %s by %s: id or attributedTo is not the signer's, ignored", reply.id(), signer);
                return;
            }
            for (ObjectOrLink target : reply.inReplyTo().stream().limit(MAX_REPLY_TARGETS).toList()) {
                Optional<StoredPost> post = target.uri().flatMap(uri -> postOf(uri, budget));
                if (post.isPresent()) {
                    SocialReply event = new SocialReply(post.get().postId(), post.get().account(), NETWORK,
                            author(create, ctx), Html.sanitize(reply.content() == null ? "" : reply.content()),
                            audience(create, reply, signer),
                            firstUri(reply.url()).filter(u -> sameHost(u, signer)).or(() -> Optional.of(reply.id())),
                            Instant.now());
                    defer(() -> replies.fire(event));
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

    private void reaction(Activity activity, InboundActivityContext ctx, SocialReaction.Type type) {
        guarded(activity, budget -> activity.objectUri().flatMap(uri -> postOf(uri, budget)).ifPresent(post -> {
            SocialReaction event = new SocialReaction(post.postId(), post.account(), NETWORK, author(activity, ctx),
                    type, Instant.now());
            defer(() -> reactions.fire(event));
        }));
    }

    /**
     * {@code Undo} of a {@code Like}/{@code Announce}/{@code Follow} by the same actor. A {@code Like} or
     * {@code Announce} is only known when embedded (the library stores neither), so an {@code Undo} that merely
     * references one fires nothing: resolving it would take a fetch. {@code UNDONE} for a {@code Follow} is fired
     * for any valid {@code Undo(Follow)} of a local actor: the built-in handler has already removed the relation by
     * the time this observer runs, so whether one existed is no longer known.
     */
    void onUndo(@Observes @Inbound Undo undo, InboundActivityContext ctx) {
        guarded(undo, budget -> {
            Optional<ASObject> object = undo.embeddedObject()
                    .or(() -> undo.objectUri().flatMap(id -> stores.objects().find(id)));
            if (object.isEmpty() || !(object.get() instanceof Activity undoneActivity)
                    || !undoneActivity.actorUri().filter(ctx.signerActor()::equals).isPresent()) {
                return;
            }
            if (undoneActivity instanceof Follow follow) {
                localActor(follow).flatMap(local -> account(local, budget)).ifPresent(account -> {
                    SocialFollow event = new SocialFollow(account, NETWORK, author(undo, ctx),
                            SocialFollow.State.UNDONE);
                    defer(() -> follows.fire(event));
                });
                return;
            }
            SocialReaction.Type type = undoneActivity instanceof Like ? SocialReaction.Type.LIKE
                    : undoneActivity instanceof Announce ? SocialReaction.Type.REPOST : null;
            if (type == null) {
                return;
            }
            undoneActivity.objectUri().flatMap(uri -> postOf(uri, budget)).ifPresent(post -> {
                SocialReactionUndone event = new SocialReactionUndone(post.postId(), post.account(), NETWORK,
                        author(undo, ctx), type, Instant.now());
                defer(() -> undone.fire(event));
            });
        });
    }

    /** After {@code FollowHandler}: the state is what the follower store holds now ({@code REJECTED} when nothing). */
    void onFollow(@Observes @Inbound Follow follow, InboundActivityContext ctx) {
        guarded(follow, budget -> localActor(follow).flatMap(local -> account(local, budget).map(account -> {
            SocialFollow.State state = stores.followers().follower(local.id(), ctx.signerActor())
                    .map(relation -> state(relation.state())).orElse(SocialFollow.State.REJECTED);
            return new SocialFollow(account, NETWORK, author(follow, ctx), state);
        })).ifPresent(event -> defer(() -> follows.fire(event))));
    }

    // ---- helpers ----

    private static SocialFollow.State state(FollowState state) {
        return switch (state) {
            case ACCEPTED -> SocialFollow.State.ACCEPTED;
            case PENDING -> SocialFollow.State.PENDING;
            case REJECTED -> SocialFollow.State.REJECTED;
        };
    }

    /** Same rule as {@code CreateUpdateHandler}: id on the signer's host, attributed to the signer alone. */
    private static boolean accepted(ASObject object, URI signer) {
        return object.id() != null && sameHost(object.id(), signer) && !object.attributedTo().isEmpty()
                && object.attributedTo().stream().allMatch(a -> a.uri().filter(signer::equals).isPresent());
    }

    private static boolean sameHost(URI a, URI b) {
        return a.getHost() != null && b.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost());
    }

    /** Public in {@code to}: PUBLIC; only in {@code cc}: UNLISTED; the replier's followers collection: FOLLOWERS_ONLY. */
    private static ReplyAudience audience(Activity activity, ASObject reply, URI signer) {
        List<URI> to = Stream.concat(reply.to().stream(), activity.to().stream()).flatMap(l -> l.uri().stream())
                .toList();
        List<URI> cc = Stream.concat(reply.cc().stream(), activity.cc().stream()).flatMap(l -> l.uri().stream())
                .toList();
        if (to.stream().anyMatch(InboundInteractions::isPublic)) {
            return ReplyAudience.PUBLIC;
        }
        if (cc.stream().anyMatch(InboundInteractions::isPublic)) {
            return ReplyAudience.UNLISTED;
        }
        URI followers = Optional.ofNullable(activity.actor()).flatMap(ObjectOrLink::object)
                .filter(Actor.class::isInstance).map(a -> ((Actor) a).followers()).orElse(null);
        boolean toFollowers = Stream.concat(to.stream(), cc.stream()).anyMatch(
                u -> u.equals(followers) || sameHost(u, signer) && u.getPath() != null && u.getPath().endsWith("/followers"));
        return toFollowers ? ReplyAudience.FOLLOWERS_ONLY : ReplyAudience.DIRECT;
    }

    private static boolean isPublic(URI uri) {
        return PUBLIC.contains(uri.toString());
    }

    private Optional<LocalActor> localActor(Follow follow) {
        return follow.objectUri().flatMap(urls::usernameOf).flatMap(actors::find);
    }

    /** The app account of a local actor: the app's {@code SocialAccountLinks}, else the adapter's default (R1). */
    private Optional<String> account(LocalActor local, Budget budget) {
        Optional<String> account = budget.await(adapter.accountFor(local.username()));
        return account == null ? Optional.empty() : account;
    }

    /** The post published with {@code ref} as the id of its ActivityPub object, with its account. */
    private Optional<StoredPost> postOf(URI ref, Budget budget) {
        Optional<String> id = budget.await(engine.findPostIdByRef(NETWORK, ref.toString()));
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return budget.await(Uni.createFrom().item(() -> social.status(id.get()))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()));
    }

    /** The sender; the embedded actor's fields only when it is the signer. */
    private SocialAuthor author(Activity activity, InboundActivityContext ctx) {
        URI id = ctx.signerActor();
        Optional<Actor> actor = Optional.ofNullable(activity.actor()).flatMap(ObjectOrLink::object)
                .filter(Actor.class::isInstance).map(Actor.class::cast).filter(a -> id.equals(a.id()));
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

    private static Optional<URI> firstUri(List<ObjectOrLink> links) {
        return links.stream().map(l -> l.uri().or(() -> l.object().map(ASObject::id))).flatMap(Optional::stream)
                .findFirst();
    }

    /** Runs {@code step} with a lookup budget; nothing it throws reaches the inbox. */
    private void guarded(Activity activity, java.util.function.Consumer<Budget> step) {
        if (!config.enabled()) {
            return;
        }
        try {
            step.accept(new Budget());
        } catch (LookupTimeout e) {
            LOG.debugf("Lookups for inbound %s %s exceeded %s, no social event", activity.getClass().getSimpleName(),
                    activity.id(), LOOKUP_BUDGET);
        } catch (RuntimeException e) {
            LOG.warnf(e, "No social event for inbound %s %s", activity.getClass().getSimpleName(), activity.id());
        }
    }

    /** Fires after the inbox transaction commits (at once when there is none); an observer's failure is contained. */
    private void defer(Runnable fire) {
        Runnable contained = () -> {
            try {
                fire.run();
            } catch (RuntimeException e) {
                LOG.warn("A social event observer failed", e);
            }
        };
        if (!JTA || !AfterCommit.register(contained)) {
            contained.run();
        }
    }

    private static boolean jtaPresent() {
        try {
            Class.forName("jakarta.transaction.TransactionSynchronizationRegistry", false,
                    InboundInteractions.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
