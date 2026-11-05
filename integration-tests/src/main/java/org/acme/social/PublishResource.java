package org.acme.social;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.sitenetsoft.quarkus.social.Capabilities;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.NetworkResult;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.PublishResult;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.SocialException;
import org.sitenetsoft.quarkus.social.Visibility;
import org.sitenetsoft.quarkus.social.store.NetworkState;
import org.sitenetsoft.quarkus.social.store.StoredPost;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;

/**
 * The signed-in user publishes with one call to every network their account is on: ActivityPub as the local actor of
 * the same name, Bluesky as the account they linked. The account id given to {@link Social} is the application's
 * principal name.
 * <p>
 * Changes ({@code POST /publish}, {@code /update}, {@code /delete}) take a form, as the application's own page would
 * post it, and must be same-origin: {@code Sec-Fetch-Site: same-origin} when the browser sends it, otherwise an
 * {@code Origin} equal to this application's; anything else is refused with 403, so another site cannot make a
 * signed-in user's browser publish. They answer the {@link PublishResult} as JSON. Endpoints run on worker threads
 * (the blocking {@link Social} refuses the event loop).
 */
@Path("/")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class PublishResource {

    @Inject
    Social social;

    @Inject
    SecurityIdentity identity;

    @Inject
    InteractionLog interactions;

    @Context
    HttpServerRequest request;

    /**
     * {@code POST /publish}: {@code text} (or {@code title} and {@code url} for an {@code ARTICLE}), optional
     * {@code id} (stable; generated when absent; publishing the same id again retries), {@code kind}
     * ({@code NOTE}, {@code ARTICLE}), {@code visibility} ({@code PUBLIC}, {@code UNLISTED}, {@code FOLLOWERS}),
     * {@code summary}.
     */
    @POST
    @Path("publish")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response publish(@HeaderParam("Sec-Fetch-Site") String fetchSite, @HeaderParam("Origin") String origin,
            @FormParam("id") String id, @FormParam("kind") String kind, @FormParam("visibility") String visibility,
            @FormParam("title") String title, @FormParam("summary") String summary, @FormParam("text") String text,
            @FormParam("url") String url) {
        return change(fetchSite, origin, () -> social.publish(account(),
                post(blank(id) ? UUID.randomUUID().toString() : id, kind, visibility, title, summary, text, url)));
    }

    /** {@code POST /update}: the same fields as {@code /publish}, {@code id} required; sends the new content. */
    @POST
    @Path("update")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response update(@HeaderParam("Sec-Fetch-Site") String fetchSite, @HeaderParam("Origin") String origin,
            @FormParam("id") String id, @FormParam("kind") String kind, @FormParam("visibility") String visibility,
            @FormParam("title") String title, @FormParam("summary") String summary, @FormParam("text") String text,
            @FormParam("url") String url) {
        return change(fetchSite, origin, () -> social.update(account(),
                post(required(id, "id"), kind, visibility, title, summary, text, url)));
    }

    /** {@code POST /delete}: {@code id}; removes the post from every network that has it. */
    @POST
    @Path("delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response delete(@HeaderParam("Sec-Fetch-Site") String fetchSite, @HeaderParam("Origin") String origin,
            @FormParam("id") String id) {
        return change(fetchSite, origin, () -> social.delete(account(), required(id, "id")));
    }

    /** {@code GET /status/{id}}: the last known state per network of one of the user's posts (404 for others). */
    @GET
    @Path("status/{id}")
    public Response status(@PathParam("id") String id) {
        Optional<StoredPost> found = social.status(id).filter(p -> p.account().equals(account()));
        return found.map(p -> Response.ok(json(p)).build()).orElseGet(() -> error(404, "no post " + id));
    }

    /** {@code GET /capabilities}: what each active network can do. */
    @GET
    @Path("capabilities")
    public Map<String, Object> capabilities() {
        Map<String, Object> out = new TreeMap<>();
        social.capabilities().forEach((network, c) -> out.put(network.id(), json(c)));
        return out;
    }

    /** {@code GET /interactions}: replies, reactions and follows the user's account received, oldest first. */
    @GET
    @Path("interactions")
    public List<Map<String, Object>> interactions() {
        return interactions.of(account());
    }

    private String account() {
        return identity.getPrincipal().getName();
    }

    private Response change(String fetchSite, String origin, Supplier<PublishResult> work) {
        Optional<String> refused = crossSite(fetchSite, origin);
        if (refused.isPresent()) {
            return error(403, refused.get());
        }
        try {
            return Response.ok(json(work.get())).build();
        } catch (SocialException e) {
            return error(409, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            return error(400, e.getMessage());
        }
    }

    /** Why the request is not a same-origin one, or empty when it is. */
    private Optional<String> crossSite(String fetchSite, String origin) {
        if (fetchSite != null) {
            return "same-origin".equalsIgnoreCase(fetchSite.strip()) ? Optional.empty()
                    : Optional.of("changes must come from this site's own pages (Sec-Fetch-Site)");
        }
        if (origin == null) {
            return Optional.of("changes must come from this site's own pages (no Origin or Sec-Fetch-Site)");
        }
        HostAndPort a = request.authority();
        String own = a == null ? null
                : request.scheme() + "://" + a.host() + (a.port() > 0 ? ":" + a.port() : "");
        return own != null && own.equalsIgnoreCase(origin.strip()) ? Optional.empty()
                : Optional.of("changes must come from this site's own pages (Origin)");
    }

    private static Post post(String id, String kind, String visibility, String title, String summary, String text,
            String url) {
        Post.Builder b = Post.builder(id.strip());
        if (!blank(kind)) {
            b.kind(Kind.valueOf(kind.strip().toUpperCase(Locale.ROOT)));
        }
        if (!blank(visibility)) {
            b.visibility(Visibility.valueOf(visibility.strip().toUpperCase(Locale.ROOT)));
        }
        if (!blank(title)) {
            b.title(title.strip());
        }
        if (!blank(summary)) {
            b.summary(summary.strip());
        }
        if (!blank(text)) {
            b.text(text.strip());
        }
        if (!blank(url)) {
            b.url(URI.create(url.strip()));
        }
        return b.build();
    }

    private static String required(String value, String name) {
        if (blank(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ---- JSON shapes (plain maps: nothing to register for reflection in a native image) ----

    static Map<String, Object> json(PublishResult r) {
        Map<String, Object> results = new TreeMap<>();
        r.results().forEach((network, result) -> results.put(network.id(), json(result)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("postId", r.postId());
        out.put("results", results);
        return out;
    }

    static Map<String, Object> json(NetworkResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (result) {
            case NetworkResult.Published p -> {
                m.put("status", "Published");
                p.url().ifPresent(u -> m.put("url", u.toString()));
                m.put("refs", new TreeMap<>(p.refs()));
                p.note().ifPresent(n -> m.put("note", n));
                m.put("resent", p.resent());
            }
            case NetworkResult.Skipped s -> {
                m.put("status", "Skipped");
                m.put("reason", s.reason().name());
                m.put("detail", s.detail());
            }
            case NetworkResult.Failed f -> {
                m.put("status", "Failed");
                m.put("code", f.code().name());
                m.put("reason", f.reason());
                m.put("retryable", f.retryable());
                m.put("refs", new TreeMap<>(f.refs()));
            }
        }
        return m;
    }

    static Map<String, Object> json(StoredPost p) {
        Map<String, Object> networks = new TreeMap<>();
        p.networks().forEach((network, s) -> networks.put(network.id(), json(s)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("postId", p.postId());
        out.put("kind", p.kind().name());
        out.put("visibility", p.visibility().name());
        out.put("networks", networks);
        return out;
    }

    static Map<String, Object> json(NetworkState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s.status().name());
        m.put("refs", new TreeMap<>(s.refs()));
        s.code().ifPresent(c -> m.put("code", c));
        s.detail().ifPresent(d -> m.put("detail", d));
        m.put("at", s.at().toString());
        s.delivery().ifPresent(d -> m.put("delivery",
                Map.of("delivered", d.delivered(), "failed", d.failed(), "pending", d.pending())));
        return m;
    }

    static Map<String, Object> json(Capabilities c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visibilities", c.visibilities().stream().map(Enum::name).sorted().toList());
        m.put("kinds", c.kinds().stream().map(Enum::name).sorted().toList());
        m.put("edits", c.edits());
        m.put("deletes", c.deletes());
        m.put("contentWarnings", c.contentWarnings());
        if (c.maxTextGraphemes().isPresent()) {
            m.put("maxTextGraphemes", c.maxTextGraphemes().getAsInt());
        }
        m.put("html", c.html());
        m.put("interactions", c.interactions());
        return m;
    }

    private static Response error(int status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", message == null ? "error" : message)).build();
    }
}
