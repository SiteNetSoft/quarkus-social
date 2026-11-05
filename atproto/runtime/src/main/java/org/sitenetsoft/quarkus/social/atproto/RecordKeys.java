package org.sitenetsoft.quarkus.social.atproto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.atproto.syntax.RecordKey;
import org.sitenetsoft.atproto.syntax.SyntaxException;
import org.sitenetsoft.atproto.syntax.Tid;

/**
 * Record keys derived from the post, so a retried write lands on the same key and finds what an earlier attempt
 * wrote. Both collections use the Lexicon key type {@code tid}: a timestamp in microseconds and a 10-bit clock id from
 * a hash of a seed (post id, collection, …).
 * <p>
 * Two posts published in the same microsecond share a key with probability 1/1024, so each record has a fixed
 * sequence of candidates: index 0 is {@code hash(seed)}, then {@value #PROBES} alternatives
 * {@code hash(seed + "\n" + i)}. A writer takes the first candidate that is free or already holds this post's record;
 * every retry walks the same sequence.
 */
public final class RecordKeys {

    static final int PROBES = 8;
    private static final String ALPHABET = "234567abcdefghijklmnopqrstuvwxyz";
    private static final long MAX_MICROS = (1L << 53) - 1;

    private RecordKeys() {
    }

    /** The candidate keys of the post's first record in {@code collection}: timestamp {@code publishedAt}. */
    public static List<RecordKey> of(String postId, Nsid collection, Instant publishedAt) {
        return sequence(micros(publishedAt), postId + "\n" + collection, null);
    }

    /**
     * The candidate keys of a record replacing {@code old} (an edit recreating the Bluesky post): the old key's
     * timestamp (the publication time when it isn't a TID) and a clock id from the post id and the old key, never the
     * old key itself. A retried edit finds the replacement an earlier attempt created.
     */
    static List<RecordKey> replacing(String postId, Nsid collection, RecordKey old, Instant publishedAt) {
        long micros;
        try {
            micros = micros(Tid.parse(old.value()).timestamp());
        } catch (SyntaxException e) {
            micros = micros(publishedAt);
        }
        return sequence(micros, postId + "\n" + collection + "\nreplacing\n" + old.value(), old);
    }

    private static List<RecordKey> sequence(long micros, String seed, RecordKey except) {
        List<RecordKey> keys = new ArrayList<>(PROBES + 1);
        for (int i = 0; i <= PROBES; i++) {
            RecordKey key = tid(micros, clockId(i == 0 ? seed : seed + "\n" + i));
            if (!key.equals(except) && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }

    private static long micros(Instant t) {
        if (t.isBefore(Instant.EPOCH)) {
            return 0;
        }
        long seconds = Math.min(t.getEpochSecond(), MAX_MICROS / 1_000_000L);
        return Math.min(seconds * 1_000_000L + t.getNano() / 1_000, MAX_MICROS);
    }

    private static long clockId(String seed) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return ((h[0] & 0xffL) << 2 | (h[1] & 0xffL) >>> 6) & 0x3FF;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The 13-character base32-sortable TID of {@code micros} and {@code clockId}. */
    private static RecordKey tid(long micros, long clockId) {
        long v = micros << 10 | clockId;
        char[] out = new char[13];
        for (int i = 0; i < 13; i++) {
            out[i] = ALPHABET.charAt((int) (v >>> (12 - i) * 5 & 31));
        }
        return RecordKey.parse(Tid.parse(new String(out)).value());
    }
}
