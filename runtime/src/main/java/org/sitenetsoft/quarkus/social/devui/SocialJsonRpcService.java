package org.sitenetsoft.quarkus.social.devui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.ConfigProvider;
import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.engine.PublishEngine;
import org.sitenetsoft.quarkus.social.spi.SocialAdapter;
import org.sitenetsoft.quarkus.social.store.NetworkState;
import org.sitenetsoft.quarkus.social.store.StoredPost;

import io.quarkus.arc.All;
import io.quarkus.runtime.LaunchMode;
import io.smallrye.mutiny.Uni;

/**
 * Dev UI backend for the "Social" card. Results are plain maps and lists. The adapters are described through the
 * {@link SocialAdapter} beans and {@link SocialDevUiContribution} beans, never through an adapter's own types.
 * <p>
 * Dev UI registers JSON-RPC services in dev mode only; {@link #publishTestPost} also refuses to run in any other
 * launch mode, because it publishes for real.
 */
public class SocialJsonRpcService {

    /** Posts listed by {@link #getRecent()}. */
    static final int RECENT = 20;
    private static final int EXCERPT = 120;

    private final PublishEngine engine;
    private final List<SocialAdapter> adapters;
    private final List<SocialDevUiContribution> contributions;
    private final LaunchMode mode;

    @Inject
    public SocialJsonRpcService(PublishEngine engine, @All List<SocialAdapter> adapters,
            @All List<SocialDevUiContribution> contributions) {
        this(engine, adapters, contributions, LaunchMode.current());
    }

    /** For tests: the launch mode the dev-only guard checks. */
    public SocialJsonRpcService(PublishEngine engine, List<SocialAdapter> adapters,
            List<SocialDevUiContribution> contributions, LaunchMode mode) {
        this.engine = engine;
        this.adapters = adapters;
        this.contributions = contributions;
        this.mode = mode;
    }

    /** The active adapters: network, capabilities and, when it can't publish right now, why. */
    public List<Map<String, Object>> getAdapters() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SocialAdapter adapter : adapters) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("network", adapter.network().id());
            row.put("capabilities", capabilities(adapter.capabilities()));
            row.put("unavailableReason", adapter.unavailableReason().orElse(null));
            out.add(row);
        }
        out.sort(Comparator.comparing(r -> (String) r.get("network")));
        return out;
    }

    /** The blocks adapters contributed (for example the Bluesky scope needed against the one configured). */
    public List<Map<String, Object>> getContributions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SocialDevUiContribution c : contributions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", c.title());
            row.put("data", new LinkedHashMap<>(c.data()));
            out.add(row);
        }
        out.sort(Comparator.comparing(r -> (String) r.get("title")));
        return out;
    }

    /** The last {@value #RECENT} posts with their per-network state, delivery progress included. */
    public Uni<List<Map<String, Object>>> getRecent() {
        return engine.recent(RECENT).map(posts -> posts.stream().map(SocialJsonRpcService::post).toList());
    }

    /**
     * Publishes a test post for real. Dev mode only. An {@code ARTICLE} gets its first text line as the title and a
     * local test URL.
     *
     * @param kind {@code NOTE} or {@code ARTICLE} (default {@code NOTE})
     * @param visibility {@code PUBLIC}, {@code UNLISTED} or {@code FOLLOWERS} (default {@code PUBLIC})
     */
    public Uni<Map<String, Object>> publishTestPost(String account, String text, String kind, String visibility) {
        if (mode != LaunchMode.DEVELOPMENT) {
            return Uni.createFrom().failure(new IllegalStateException("publishTestPost works in dev mode only"));
        }
        Post post;
        try {
            post = testPost(text, kind, visibility);
            if (account == null || account.isBlank()) {
                throw new IllegalArgumentException("an account is required");
            }
        } catch (RuntimeException e) {
            return Uni.createFrom().failure(e);
        }
        return engine.publish(account.trim(), post).map(result -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("postId", result.postId());
            Map<String, Object> networks = new LinkedHashMap<>();
            result.results().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(n -> n.id())))
                    .forEach(e -> networks.put(e.getKey().id(), result(e.getValue())));
            out.put("results", networks);
            return out;
        });
    }

    private static Post testPost(String text, String kind, String visibility) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        Kind k = kind == null || kind.isBlank() ? Kind.NOTE : Kind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        Visibility v = visibility == null || visibility.isBlank() ? Visibility.PUBLIC
                : Visibility.valueOf(visibility.trim().toUpperCase(Locale.ROOT));
        String id = "dev-ui-test-" + UUID.randomUUID().toString().substring(0, 8);
        Post.Builder b = Post.builder(id).kind(k).visibility(v).text(text.strip());
        if (k == Kind.ARTICLE) {
            String first = text.strip().lines().findFirst().orElse("Test post");
            b.title(first.length() > 80 ? first.substring(0, 80) : first);
            int port = ConfigProvider.getConfig().getOptionalValue("quarkus.http.port", Integer.class).orElse(8080);
            b.url(java.net.URI.create("http://localhost:" + port + "/dev-ui-test/" + id));
        }
        return b.build();
    }

    private static Map<String, Object> capabilities(Capabilities c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visibilities", c.visibilities().stream().map(Enum::name).sorted().toList());
        m.put("kinds", c.kinds().stream().map(Enum::name).sorted().toList());
        m.put("edits", c.edits());
        m.put("deletes", c.deletes());
        m.put("contentWarnings", c.contentWarnings());
        m.put("maxTextGraphemes", c.maxTextGraphemes().isPresent() ? c.maxTextGraphemes().getAsInt() : null);
        m.put("html", c.html());
        m.put("interactions", c.interactions());
        return m;
    }

    private static Map<String, Object> post(StoredPost p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("postId", p.postId());
        m.put("account", p.account());
        m.put("kind", p.kind().name());
        m.put("visibility", p.visibility().name());
        String text = p.snapshot().title().or(() -> p.snapshot().text()).or(() -> p.snapshot().html()).orElse("");
        m.put("excerpt", text.length() > EXCERPT ? text.substring(0, EXCERPT) + "..." : text);
        List<Map<String, Object>> networks = new ArrayList<>();
        p.networks().values().stream().sorted(Comparator.comparing(s -> s.network().id()))
                .forEach(s -> networks.add(state(s)));
        m.put("networks", networks);
        return m;
    }

    private static Map<String, Object> state(NetworkState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("network", s.network().id());
        m.put("status", s.status().name());
        m.put("code", s.code().orElse(null));
        m.put("detail", s.detail().orElse(null));
        m.put("url", s.refs().get(NetworkState.URL_REF));
        Map<String, String> refs = new LinkedHashMap<>(s.refs());
        refs.remove(NetworkState.URL_REF);
        m.put("refs", refs);
        m.put("at", s.at().toString());
        m.put("delivery", s.delivery().map(d -> {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("delivered", d.delivered());
            dm.put("failed", d.failed());
            dm.put("pending", d.pending());
            return dm;
        }).orElse(null));
        return m;
    }

    private static Map<String, Object> result(NetworkResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (r) {
            case NetworkResult.Published p -> {
                m.put("outcome", "published");
                m.put("url", p.url().map(Object::toString).orElse(null));
                m.put("note", p.note().orElse(null));
                m.put("resent", p.resent());
            }
            case NetworkResult.Skipped s -> {
                m.put("outcome", "skipped");
                m.put("reason", s.reason().name());
                m.put("detail", s.detail());
            }
            case NetworkResult.Failed f -> {
                m.put("outcome", "failed");
                m.put("code", f.code().name());
                m.put("reason", f.reason());
                m.put("retryable", f.retryable());
            }
        }
        return m;
    }
}
