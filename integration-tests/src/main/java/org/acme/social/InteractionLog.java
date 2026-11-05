package org.acme.social;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.sitenetsoft.quarkus.social.events.SocialAuthor;
import org.sitenetsoft.quarkus.social.events.SocialFollow;
import org.sitenetsoft.quarkus.social.events.SocialReaction;
import org.sitenetsoft.quarkus.social.events.SocialReactionUndone;
import org.sitenetsoft.quarkus.social.events.SocialReply;

/**
 * The social events the sample observed (replies, likes and reposts, their undoing, follows), newest last, the last
 * {@value #MAX} kept in memory. A real application stores what it wants to show; replies carry an {@code audience},
 * and only {@code PUBLIC} and {@code UNLISTED} ones are meant to be rendered publicly.
 */
@ApplicationScoped
public class InteractionLog {

    static final int MAX = 200;

    private final Deque<Map<String, Object>> entries = new ArrayDeque<>();

    void onReply(@Observes SocialReply e) {
        Map<String, Object> m = entry("reply", e.account(), e.network().id(), e.author());
        m.put("postId", e.postId());
        m.put("audience", e.audience().name());
        m.put("html", e.html());
        e.url().ifPresent(u -> m.put("url", u.toString()));
        m.put("receivedAt", e.receivedAt().toString());
        add(m);
    }

    void onReaction(@Observes SocialReaction e) {
        Map<String, Object> m = entry(e.type().name().toLowerCase(java.util.Locale.ROOT), e.account(),
                e.network().id(), e.author());
        m.put("postId", e.postId());
        m.put("receivedAt", e.receivedAt().toString());
        add(m);
    }

    void onReactionUndone(@Observes SocialReactionUndone e) {
        Map<String, Object> m = entry(e.type().name().toLowerCase(java.util.Locale.ROOT) + "-undone", e.account(),
                e.network().id(), e.author());
        m.put("postId", e.postId());
        add(m);
    }

    void onFollow(@Observes SocialFollow e) {
        Map<String, Object> m = entry("follow", e.account(), e.network().id(), e.author());
        m.put("state", e.state().name());
        add(m);
    }

    /** What {@code account} observed, oldest first. */
    synchronized List<Map<String, Object>> of(String account) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            if (account.equals(e.get("account"))) {
                out.add(e);
            }
        }
        return out;
    }

    private synchronized void add(Map<String, Object> entry) {
        entries.addLast(entry);
        while (entries.size() > MAX) {
            entries.removeFirst();
        }
    }

    private static Map<String, Object> entry(String type, String account, String network, SocialAuthor author) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("account", account);
        m.put("network", network);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", author.id());
        author.handle().ifPresent(h -> a.put("handle", h));
        author.displayName().ifPresent(n -> a.put("displayName", n));
        author.profileUrl().ifPresent(u -> a.put("profileUrl", u.toString()));
        m.put("author", a);
        return m;
    }
}
