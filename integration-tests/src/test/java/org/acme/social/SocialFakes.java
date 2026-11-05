package org.acme.social;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import org.sitenetsoft.activitystreams.ASJson;
import org.sitenetsoft.activitystreams.Follow;
import org.sitenetsoft.activitystreams.ObjectOrLink;
import org.sitenetsoft.httpsignatures.Scheme;
import org.sitenetsoft.quarkus.activitypub.test.FakeActor;
import org.sitenetsoft.quarkus.activitypub.test.FakeFediverseServer;
import org.sitenetsoft.quarkus.activitypub.test.ReceivedRequest;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.atproto.test.FakeDns;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Both fake networks, started in the test JVM, and the configuration that points the packaged sample application (a
 * separate process under {@code @QuarkusIntegrationTest}, normal launch mode, profile {@code test}) at them:
 * <ul>
 * <li><b>ActivityPub</b>: a {@link FakeFediverseServer} on {@code 127.0.0.1}. The application's
 * {@code quarkus.activitypub.base-url} is its own loopback origin ({@code http://127.0.0.1:<quarkus.http.test-port>}),
 * so the fake fetches the actor {@code ann} and her inbox from it; safe-fetch allows {@code http} and loopback.</li>
 * <li><b>AT Protocol</b>, as in {@code quarkus-atproto}'s link-mode IT: {@link FakeAtproto} (PLC directory,
 * authorization server, PDS) and a {@link FakeDns} for the handle; the application is a confidential client with
 * {@code client-base-url} {@value #BASE} (an ES256 key and a session encryption key generated per run, in
 * {@code target/it-secrets.properties}, passed with {@code quarkus.config.locations} so they stay out of the launch
 * command line). The fake authorization server fetches the client metadata of {@value #BASE} from the application's
 * port.</li>
 * </ul>
 * {@link #ready()} links {@value #HANDLE} to the application user {@code ann} (the {@code quarkus-atproto} link flow)
 * and makes {@code carol} on the fake fediverse follow {@code ann}, once per application start.
 */
public class SocialFakes implements QuarkusTestResourceLifecycleManager {

    static final String BASE = "https://app.example.test";
    static final String HANDLE = "alice.example.test";
    static final Duration WAIT = Duration.ofSeconds(30);

    private static volatile FakeAtproto atproto;
    private static volatile FakeDns dns;
    private static volatile FakeFediverseServer fediverse;
    private static volatile boolean ready;

    static FakeAtproto atproto() {
        return atproto;
    }

    static FakeFediverseServer fediverse() {
        return fediverse;
    }

    static FakeAccount alice() {
        return atproto.account(HANDLE);
    }

    /** The port the application listens on. */
    static int appPort() {
        return Integer.parseInt(System.getProperty("quarkus.http.test-port", "8081"));
    }

    /** The application's own origin, also its ActivityPub base URL. */
    static String app() {
        return "http://127.0.0.1:" + appPort();
    }

    @Override
    public Map<String, String> start() {
        ready = false;
        atproto = FakeAtproto.start();
        dns = FakeDns.start(0);
        dns.handle(atproto.account(HANDLE));
        atproto.as().rewriteClientFetches(BASE, app());
        fediverse = new FakeFediverseServer("127.0.0.1").start();

        Map<String, String> config = new HashMap<>();
        config.put("quarkus.activitypub.base-url", app());
        config.put("quarkus.atproto.oauth.client-base-url", BASE);
        // the per-run secrets go through a config file, not -D system properties (those are printed in the
        // launcher's "Executing" log line)
        config.put("quarkus.config.locations", secretsFile().toUri().toString());
        config.put("quarkus.did.plc.url", atproto.plcUrl().toString());
        config.put("quarkus.atproto.identity.dns.servers", dns.server());
        return config;
    }

    @Override
    public void stop() {
        if (fediverse != null) {
            fediverse.stop();
        }
        if (atproto != null) {
            atproto.close();
        }
        if (dns != null) {
            dns.close();
        }
    }

    /**
     * {@code ann} has linked {@value #HANDLE} and {@code carol} follows {@code ann} (done on the first call after the
     * application started).
     */
    static synchronized void ready() {
        if (ready) {
            return;
        }
        Browser ann = Browser.ann();
        assertEquals(302, ann.signIn(HANDLE).statusCode(), "linking alice's account");

        FakeActor carol = fediverse.actor("carol");
        Follow follow = new Follow();
        follow.id(URI.create(fediverse.baseUri() + "/activities/" + UUID.randomUUID()));
        follow.actor(ObjectOrLink.of(carol.id())).object(ObjectOrLink.of(URI.create(app() + "/users/ann")));
        assertEquals(202, carol.post(URI.create(app() + "/users/ann/inbox"), follow, Scheme.CAVAGE).statusCode());
        await().atMost(WAIT).until(() -> received("Accept", follow.id().toString()).isPresent());
        ready = true;
    }

    /** The activities of {@code type} about {@code object} (its id, or an embedded object's id) the fake received. */
    static List<JsonNode> activities(String type, String object) {
        return fediverse.received().stream()
                .filter(r -> r.method().equals("POST") && r.path().equals("/inbox"))
                .filter(ReceivedRequest::verified)
                .map(r -> tree(r.bodyAsString()))
                .filter(a -> type.equals(a.path("type").asText()))
                .filter(a -> object.equals(a.path("object").isTextual() ? a.path("object").asText()
                        : a.path("object").path("id").asText()))
                .toList();
    }

    static Optional<JsonNode> received(String type, String object) {
        return activities(type, object).stream().findFirst();
    }

    /** Waits for the first activity of {@code type} about {@code object}. */
    static JsonNode awaitActivity(String type, String object) {
        return await().atMost(WAIT).until(() -> received(type, object), Optional::isPresent).orElseThrow();
    }

    private static JsonNode tree(String json) {
        try {
            return ASJson.mapper().readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code target/it-secrets.properties}: the signing key location and the session encryption key. */
    private static Path secretsFile() {
        try {
            Path file = Path.of("target", "it-secrets.properties").toAbsolutePath();
            Properties p = new Properties();
            p.setProperty("quarkus.atproto.oauth.signing-key.location", signingKey().toString());
            p.setProperty("quarkus.atproto.oauth.session.encryption-key", randomKey());
            try (var out = Files.newOutputStream(file)) {
                p.store(out, "per-run secrets of the integration tests");
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A fresh ES256 key as a PKCS#8 PEM file under {@code target/}. */
    private static Path signingKey() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = g.generateKeyPair();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(pair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            Path file = Path.of("target", "it-client-key.pem").toAbsolutePath();
            Files.createDirectories(file.getParent());
            Files.writeString(file, pem);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomKey() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
