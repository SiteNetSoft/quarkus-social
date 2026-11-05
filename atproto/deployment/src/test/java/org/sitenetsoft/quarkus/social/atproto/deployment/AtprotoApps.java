package org.sitenetsoft.quarkus.social.atproto.deployment;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.ConfigProvider;
import org.sitenetsoft.quarkus.atproto.identity.TxtResolver;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Post;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.smallrye.mutiny.Uni;

/**
 * The application under test of the publishing tests: {@code quarkus-atproto-oauth} in {@code link} mode against the
 * fakes, the application's own sign-in being basic auth with the embedded users {@value #ANN} and {@value #BEN}.
 */
final class AtprotoApps {

    static final String ANN = "ann";
    static final String BEN = "ben";
    static final String PASSWORD = "secret-password";
    static final String BINDING = "atproto-session-binding";
    /** The default output's scope. */
    static final String SCOPE = "atproto repo:app.bsky.feed.post?action=create&action=delete "
            + "repo:site.standard.document?action=create&action=update&action=delete";

    static final Instant PUBLISHED = Instant.parse("2026-11-04T10:15:30.123456Z");

    private static final HttpClient JDK = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private AtprotoApps() {
    }

    static QuarkusExtensionTest linkMode(FakeAtproto fake, List<String> handles, Class<?>... classes) {
        QuarkusExtensionTest test = new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(AtprotoApps.class, NoDns.class).addClasses(classes))
                // the development client's redirect URI is on 127.0.0.1
                .overrideConfigKey("quarkus.http.host", "127.0.0.1")
                .overrideConfigKey("quarkus.safe-fetch.https-only", "false")
                .overrideConfigKey("quarkus.safe-fetch.allow-private-addresses", "true")
                .overrideConfigKey("quarkus.did.plc.url", fake.plcUrl().toString())
                .overrideConfigKey("quarkus.atproto.oauth.scope", SCOPE)
                .overrideConfigKey("quarkus.atproto.oauth.mode", "link")
                .overrideConfigKey("quarkus.http.auth.basic", "true")
                .overrideConfigKey("quarkus.security.users.embedded.enabled", "true")
                .overrideConfigKey("quarkus.security.users.embedded.plain-text", "true")
                .overrideConfigKey("quarkus.security.users.embedded.users." + ANN, PASSWORD)
                .overrideConfigKey("quarkus.security.users.embedded.users." + BEN, PASSWORD)
                .overrideConfigKey("quarkus.social.timeout", "20s");
        for (String h : handles) {
            test.overrideConfigKey("quarkus.atproto.identity.https-well-known-override.\"" + h + "\"",
                    fake.wellKnownBase(h).toString());
        }
        return test;
    }

    /**
     * Links {@code handle}'s account to the application user {@code user}: the link starts from the application's
     * own page, the fake authorization server approves, the callback comes back in the same browser.
     */
    static void link(String user, String handle) {
        Response login = given().redirects().follow(false).auth().preemptive().basic(user, PASSWORD)
                .header("Sec-Fetch-Site", "same-origin").contentType("application/x-www-form-urlencoded")
                .formParam("handle", handle).post("/atproto/login");
        assertEquals(302, login.statusCode(), () -> "login: " + login.body().asString());
        String binding = login.cookie(BINDING);
        assertNotNull(binding, "no binding cookie");
        HttpResponse<String> approved = jdkGet(URI.create(login.header("Location")));
        assertEquals(302, approved.statusCode(), approved::body);
        URI callback = URI.create(approved.headers().firstValue("Location").orElseThrow());
        String local = "http://127.0.0.1:" + port() + callback.getRawPath() + "?" + callback.getRawQuery();
        Response cb = given().redirects().follow(false).urlEncodingEnabled(false).auth().preemptive()
                .basic(user, PASSWORD).cookie(BINDING, binding).get(local);
        assertEquals(302, cb.statusCode(), () -> "callback: " + cb.body().asString());
    }

    /** An ARTICLE with a summary, published at {@link #PUBLISHED}. */
    static Post.Builder article(String id) {
        return Post.builder(id).kind(Kind.ARTICLE).title("Hello world").summary("A first post")
                .html("<p>Body.</p>").url(URI.create("https://blog.example/hello")).language("en")
                .tags(List.of("quarkus")).publishedAt(PUBLISHED);
    }

    static int port() {
        return ConfigProvider.getConfig().getValue("quarkus.http.test-port", Integer.class);
    }

    private static HttpResponse<String> jdkGet(URI uri) {
        try {
            return JDK.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** No DNS in tests: handles resolve through the well-known override. */
    @ApplicationScoped
    public static class NoDns implements TxtResolver {
        @Override
        public Uni<List<String>> txt(String name) {
            return Uni.createFrom().item(List.of());
        }
    }
}
