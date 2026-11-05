package org.acme.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A browser for the packaged application, redirects followed by hand: it keeps cookies per name, signs in to the
 * application with basic auth (user {@code ann}), and sends form posts as a browser on the application's own page
 * would ({@code Sec-Fetch-Site: same-origin}).
 */
final class Browser {

    static final String BINDING = "atproto-session-binding";
    /** {@code %test.quarkus.security.users.embedded.users.ann} in application.properties. */
    static final String ANN_PASSWORD = "ann-password";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient JDK = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final String basicAuth;

    private Browser(String basicAuth) {
        this.basicAuth = basicAuth;
    }

    static Browser anonymous() {
        return new Browser(null);
    }

    /** Signed in to the application as {@code ann}. */
    static Browser ann() {
        return new Browser("Basic " + Base64.getEncoder()
                .encodeToString(("ann:" + ANN_PASSWORD).getBytes(StandardCharsets.UTF_8)));
    }

    HttpResponse<String> get(String pathAndQuery) {
        return send(request(SocialFakes.app() + pathAndQuery).GET());
    }

    /** A form post from one of the application's own pages. */
    HttpResponse<String> postForm(String path, Map<String, String> form) {
        return postForm(path, form, Map.of("Sec-Fetch-Site", "same-origin"));
    }

    /** A form post with exactly these browser headers ({@code Sec-Fetch-Site}, {@code Origin}). */
    HttpResponse<String> postForm(String path, Map<String, String> form, Map<String, String> headers) {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        HttpRequest.Builder b = request(SocialFakes.app() + path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return send(b);
    }

    /** {@code POST} of a form from the application's page, answered with a 200 JSON body. */
    JsonNode postJson(String path, Map<String, String> form) {
        return json(postForm(path, form));
    }

    /** {@code GET}, answered with a 200 JSON body. */
    JsonNode getJson(String pathAndQuery) {
        return json(get(pathAndQuery));
    }

    /**
     * Links an AT Protocol account to the signed-in application user, from the application's page: the link-mode login
     * → the authorization server (auto-approves) → the callback (its URL's path and query, on the application's port,
     * with the binding cookie). Returns the callback's answer.
     */
    HttpResponse<String> signIn(String handle) {
        HttpResponse<String> login = postForm("/atproto/login", Map.of("handle", handle, "redirect", "/"));
        assertEquals(302, login.statusCode(), () -> "login: " + login.statusCode() + " " + login.body());
        URI authorize = URI.create(login.headers().firstValue("Location").orElseThrow());
        HttpResponse<String> approved = sendRaw(HttpRequest.newBuilder(authorize).GET());
        assertEquals(302, approved.statusCode(), () -> "authorize: " + approved.statusCode() + " " + approved.body());
        URI callback = URI.create(approved.headers().firstValue("Location").orElseThrow());
        assertEquals(SocialFakes.BASE + "/atproto/oauth/callback", callback.getScheme() + "://" + callback.getHost()
                + callback.getPath(), callback::toString);
        assertNotNull(cookies.get(BINDING), "no binding cookie after login");
        return get(callback.getRawPath() + "?" + callback.getRawQuery());
    }

    static JsonNode json(HttpResponse<String> r) {
        assertEquals(200, r.statusCode(), () -> r.uri() + ": " + r.statusCode() + " " + r.body());
        try {
            return MAPPER.readTree(r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
        if (!cookies.isEmpty()) {
            b.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        if (basicAuth != null) {
            b.header("Authorization", basicAuth);
        }
        return b;
    }

    private HttpResponse<String> send(HttpRequest.Builder b) {
        HttpResponse<String> r = sendRaw(b);
        for (String setCookie : r.headers().allValues("Set-Cookie")) {
            String pair = setCookie.split(";", 2)[0];
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq).strip();
            String value = pair.substring(eq + 1).strip();
            boolean expired = setCookie.toLowerCase(java.util.Locale.ROOT).contains("max-age=0");
            if (value.isEmpty() || expired) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
        return r;
    }

    private static HttpResponse<String> sendRaw(HttpRequest.Builder b) {
        try {
            return JDK.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
