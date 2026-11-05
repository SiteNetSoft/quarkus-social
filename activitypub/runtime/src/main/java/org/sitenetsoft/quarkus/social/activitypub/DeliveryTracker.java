package org.sitenetsoft.quarkus.social.activitypub;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.activitypub.outbound.DeliveryEvents.DeliveriesSubmitted;
import org.sitenetsoft.quarkus.activitypub.outbound.DeliveryEvents.DeliveryCompleted;
import org.sitenetsoft.quarkus.activitypub.outbound.DeliveryEvents.DeliveryFailed;
import org.sitenetsoft.quarkus.activitypub.store.Delivery;
import org.sitenetsoft.quarkus.activitypub.store.DeliveryState;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.engine.PublishEngine;
import org.sitenetsoft.quarkus.social.store.DeliveryProgress;
import org.sitenetsoft.quarkus.social.store.NetworkState;

import io.vertx.core.Context;

/**
 * Turns {@code quarkus-activitypub}'s delivery events into {@link PublishEngine#recordProgress} calls: per post, the
 * counts of its latest activity ({@code Create}, then each {@code Update} or the {@code Delete}).
 * <p>
 * The adapter opens a {@link Call} around each blocking publish; the {@link DeliveriesSubmitted} fired on that thread
 * inside the call names every delivery of the activity, so the counts are computed from the full delivery list and
 * every later event, never by adding up events: a progress write the engine drops (no state yet) is made good by the
 * next one. Progress is written only on change, per post one write at a time, the last one with the latest counts.
 * An entry is forgotten once nothing is pending.
 * <p>
 * {@code DeliveriesSubmitted} fires inside the publish transaction, so it is provisional: a call that fails forgets
 * what it registered. A delivery event that arrives before the activity is registered (a store without transactions
 * lets the delivery job send before the publishing thread fires the event) is kept briefly and merged when it is.
 * Deliveries created before this application started were never registered here (they were queued before a restart):
 * their post is looked up with {@link PublishEngine#findPostIdByRef} and its stored counts moved by one, best effort.
 */
@ApplicationScoped
public class DeliveryTracker {

    private static final Logger LOG = Logger.getLogger(DeliveryTracker.class);
    private static final Network NETWORK = Network.ACTIVITYPUB;
    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();
    private static final int MAX_EARLY = 1024;
    private static final Duration EARLY_TTL = Duration.ofMinutes(2);
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(30);

    @Inject
    PublishEngine engine;
    @Inject
    Clock clock;

    /** Per post id and per current activity id: the same entries. Guarded by {@code this}. */
    private final Map<String, Tracked> byPost = new HashMap<>();
    private final Map<URI, Tracked> byActivity = new HashMap<>();
    /** Delivery id → final state, for events that came before their activity was registered. Guarded by {@code this}. */
    private final LinkedHashMap<String, Early> early = new LinkedHashMap<>();
    private final Object restoredLock = new Object();
    private Instant started;

    @PostConstruct
    void init() {
        started = clock.instant();
    }

    /**
     * Opens a publishing call for {@code postId} on this thread: the {@link DeliveriesSubmitted} fired on it before
     * {@link Call#close()} (of {@code expectedActivity} when not null) is the call's.
     */
    Call begin(String postId, URI expectedActivity) {
        Call call = new Call(postId, expectedActivity);
        CURRENT.set(call);
        return call;
    }

    void onSubmitted(@Observes DeliveriesSubmitted event) {
        Call call = CURRENT.get();
        if (call == null || call.submitted != null
                || call.expected != null && !call.expected.equals(event.activityId())) {
            return; // not published by this adapter
        }
        call.submitted = event;
        synchronized (this) {
            Tracked t = byPost.get(call.postId);
            if (t == null) {
                t = new Tracked(call.postId);
                byPost.put(call.postId, t);
            } else {
                call.previousActivity = t.activityId;
                call.previousStates = new HashMap<>(t.states);
                byActivity.remove(t.activityId);
                t.states.clear();
            }
            t.activityId = event.activityId();
            t.open++;
            boolean merged = false;
            for (Delivery d : event.deliveries()) {
                Early e = early.remove(d.id());
                merged |= e != null;
                t.states.put(d.id(), e != null ? e.state : d.state());
            }
            byActivity.put(event.activityId(), t);
            call.tracked = t;
            // nothing will come later for a finished list; merged early events still need writing
            call.pushOnSuccess = merged || t.progress().pending() == 0;
        }
    }

    void onCompleted(@Observes DeliveryCompleted event) {
        changed(event.delivery());
    }

    void onFailed(@Observes DeliveryFailed event) {
        changed(event.delivery());
    }

    private void changed(Delivery d) {
        boolean restored = false;
        Tracked send = null;
        synchronized (this) {
            Tracked t = byActivity.get(d.activityId());
            if (t != null) {
                DeliveryState old = t.states.put(d.id(), d.state());
                if (old != d.state() && claim(t)) {
                    send = t;
                }
            } else if (CURRENT.get() != null) {
                // failed at submit inside one of our calls: the DeliveriesSubmitted that follows lists it
            } else if (d.created().isBefore(started)) {
                restored = true;
            } else if (d.state() != DeliveryState.PENDING) {
                keepEarly(d);
            }
        }
        if (send != null) {
            send(send);
        } else if (restored && d.state() != DeliveryState.PENDING) {
            countRestored(d);
        }
    }

    /** Marks {@code t} as changed; true when the caller must start writing (no write in flight). */
    private boolean claim(Tracked t) {
        t.dirty = true;
        if (t.sending) {
            return false;
        }
        t.sending = true;
        return true;
    }

    private void send(Tracked t) {
        DeliveryProgress progress;
        synchronized (this) {
            t.dirty = false;
            progress = t.progress();
        }
        engine.recordProgress(t.postId, NETWORK, progress).subscribe().with(ok -> sent(t), failure -> {
            LOG.warnf(failure, "delivery progress of post %s not stored", t.postId);
            sent(t);
        });
    }

    private void sent(Tracked t) {
        synchronized (this) {
            if (!t.dirty) {
                t.sending = false;
                forgetIfDone(t);
                return;
            }
        }
        send(t);
    }

    /** Forgets a finished entry once no call is open on it and no write is in flight. */
    private void forgetIfDone(Tracked t) {
        if (t.open == 0 && !t.sending && t.progress().pending() == 0 && byPost.get(t.postId) == t) {
            byPost.remove(t.postId);
            byActivity.remove(t.activityId);
        }
    }

    private void keepEarly(Delivery d) {
        Instant now = clock.instant();
        for (Iterator<Early> it = early.values().iterator(); it.hasNext();) {
            Early e = it.next();
            if (early.size() < MAX_EARLY && e.at.plus(EARLY_TTL).isAfter(now)) {
                break;
            }
            it.remove();
        }
        early.put(d.id(), new Early(d.state(), now));
    }

    /**
     * A delivery queued before this application started: its post's stored counts move one from pending to
     * delivered or failed. Serialised; blocks the delivery thread for the store calls (never an event loop).
     */
    private void countRestored(Delivery d) {
        if (Context.isOnEventLoopThread()) {
            LOG.debugf("delivery %s of %s not counted: event on the event loop", d.id(), d.activityId());
            return;
        }
        synchronized (restoredLock) {
            try {
                Optional<String> postId = engine.findPostIdByRef(NETWORK, d.activityId().toString()).await()
                        .atMost(LOOKUP_TIMEOUT);
                if (postId.isEmpty()) {
                    return;
                }
                Optional<DeliveryProgress> stored = engine.status(postId.get()).await().atMost(LOOKUP_TIMEOUT)
                        .flatMap(p -> Optional.ofNullable(p.networks().get(NETWORK)))
                        .flatMap(NetworkState::delivery);
                if (stored.isEmpty() || stored.get().pending() == 0) {
                    return;
                }
                DeliveryProgress p = stored.get();
                DeliveryProgress next = d.state() == DeliveryState.DELIVERED
                        ? new DeliveryProgress(p.delivered() + 1, p.failed(), p.pending() - 1)
                        : new DeliveryProgress(p.delivered(), p.failed() + 1, p.pending() - 1);
                engine.recordProgress(postId.get(), NETWORK, next).await().atMost(LOOKUP_TIMEOUT);
            } catch (RuntimeException e) {
                LOG.warnf(e, "delivery %s of %s not counted", d.id(), d.activityId());
            }
        }
    }

    /** Per post: the deliveries of its latest activity and the write state. Guarded by the tracker. */
    private static final class Tracked {
        final String postId;
        URI activityId;
        final Map<String, DeliveryState> states = new HashMap<>();
        /** Calls registered on this entry and not closed yet. */
        int open;
        boolean sending;
        boolean dirty;

        Tracked(String postId) {
            this.postId = postId;
        }

        DeliveryProgress progress() {
            int delivered = 0;
            int failed = 0;
            int pending = 0;
            for (DeliveryState state : states.values()) {
                switch (state) {
                    case DELIVERED -> delivered++;
                    case FAILED, GIVEN_UP -> failed++;
                    case PENDING -> pending++;
                }
            }
            return new DeliveryProgress(delivered, failed, pending);
        }
    }

    private record Early(DeliveryState state, Instant at) {
    }

    /** One blocking publish by the adapter; close it in {@code finally}, after {@link #succeeded()} on success. */
    final class Call implements AutoCloseable {
        private final String postId;
        private final URI expected;
        private DeliveriesSubmitted submitted;
        private Tracked tracked;
        private URI previousActivity;
        private Map<String, DeliveryState> previousStates;
        private boolean pushOnSuccess;
        private boolean succeeded;

        private Call(String postId, URI expected) {
            this.postId = postId;
            this.expected = expected;
        }

        /** The submission observed during the call, empty when none was fired (nothing published). */
        Optional<DeliveriesSubmitted> submitted() {
            return Optional.ofNullable(submitted);
        }

        /** The publish committed: its deliveries are real. */
        void succeeded() {
            succeeded = true;
        }

        @Override
        public void close() {
            CURRENT.remove();
            if (tracked == null) {
                return;
            }
            Tracked send = null;
            synchronized (DeliveryTracker.this) {
                Tracked t = tracked;
                t.open--;
                if (!succeeded) {
                    abandon(t);
                } else if (pushOnSuccess && claim(t)) {
                    send = t;
                } else {
                    forgetIfDone(t);
                }
            }
            if (send != null) {
                send(send);
            }
        }

        /** The publish failed (rolled back): the activity's deliveries don't exist; the post's previous one is back. */
        private void abandon(Tracked t) {
            if (!t.activityId.equals(submitted.activityId())) {
                return; // another call replaced it meanwhile
            }
            byActivity.remove(t.activityId);
            t.states.clear();
            if (previousActivity != null) {
                t.activityId = previousActivity;
                t.states.putAll(previousStates);
                byActivity.put(previousActivity, t);
            } else if (t.open == 0 && !t.sending && byPost.get(t.postId) == t) {
                byPost.remove(t.postId);
            }
        }
    }
}
