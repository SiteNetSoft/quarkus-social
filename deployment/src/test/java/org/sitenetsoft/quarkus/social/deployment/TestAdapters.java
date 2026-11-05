package org.sitenetsoft.quarkus.social.deployment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.events.SocialDeliveryProgress;
import org.sitenetsoft.quarkus.social.test.RecordingSocialAdapter;

/** Two recording adapters (networks {@code test-a}, {@code test-b}) and a progress-event observer. */
final class TestAdapters {

    static final Network A = new Network("test-a");
    static final Network B = new Network("test-b");

    private TestAdapters() {
    }

    @Singleton
    public static class AdapterA extends RecordingSocialAdapter {
        public AdapterA() {
            super(A);
        }
    }

    @Singleton
    public static class AdapterB extends RecordingSocialAdapter {
        public AdapterB() {
            super(B);
        }
    }

    @Singleton
    public static class ProgressEvents {
        final List<SocialDeliveryProgress> received = new CopyOnWriteArrayList<>();

        void on(@Observes SocialDeliveryProgress event) {
            received.add(event);
        }
    }
}
