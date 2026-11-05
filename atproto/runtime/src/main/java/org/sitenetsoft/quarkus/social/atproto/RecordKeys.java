package org.sitenetsoft.quarkus.social.atproto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.atproto.syntax.RecordKey;
import org.sitenetsoft.atproto.syntax.Tid;

/**
 * Record keys derived from the post, so a retried write lands on the same key and finds what an earlier attempt
 * wrote. Both collections use the Lexicon key type {@code tid}: the timestamp is the given time in microseconds, the
 * 10-bit clock id comes from a hash of the post id, the collection and a salt.
 */
final class RecordKeys {

    private static final String ALPHABET = "234567abcdefghijklmnopqrstuvwxyz";
    private static final long MAX_MICROS = (1L << 53) - 1;

    private RecordKeys() {
    }

    /** The key of the post's first record in {@code collection}: timestamp {@code publishedAt}. */
    static RecordKey of(String postId, Nsid collection, Instant publishedAt) {
        return tid(micros(publishedAt), clockId(postId + "\n" + collection));
    }

    /**
     * The key of a record that replaces an earlier one at {@code now} (an edit recreating the Bluesky post): a different
     * clock-id salt, so it differs from {@link #of} even at the same microsecond.
     */
    static RecordKey replacement(String postId, Nsid collection, Instant now) {
        return tid(micros(now), clockId(postId + "\n" + collection + "\nreplacement"));
    }

    private static long micros(Instant t) {
        long micros = Math.multiplyExact(t.getEpochSecond(), 1_000_000L) + t.getNano() / 1_000;
        return Math.min(Math.max(micros, 0), MAX_MICROS);
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
