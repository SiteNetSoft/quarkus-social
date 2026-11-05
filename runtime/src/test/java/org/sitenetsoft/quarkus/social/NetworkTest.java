package org.sitenetsoft.quarkus.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NetworkTest {

    @Test
    void rejectsBadId() {
        for (String bad : new String[] { "", "A", "1x", "a b", "a_b", "-a" }) {
            assertThrows(IllegalArgumentException.class, () -> new Network(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new Network(null));
    }

    @Test
    void equalityById() {
        assertEquals(Network.ACTIVITYPUB, new Network("activitypub"));
        assertEquals(Network.ATPROTO.hashCode(), new Network("atproto").hashCode());
    }
}
