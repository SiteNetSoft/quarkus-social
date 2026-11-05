package org.sitenetsoft.quarkus.social.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.PublishResult;
import org.sitenetsoft.quarkus.social.Social;
import org.sitenetsoft.quarkus.social.SocialException;

import io.quarkus.test.QuarkusUnitTest;

/** Two or more concurrent publish calls with one post id: each network gets the post once. */
public class ConcurrencyTest {

    private static final int CALLERS = 8;

    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(TestAdapters.class, TestAdapters.AdapterA.class,
                    TestAdapters.AdapterB.class, TestAdapters.ProgressEvents.class));

    @Inject
    Social social;
    @Inject
    TestAdapters.AdapterA a;
    @Inject
    TestAdapters.AdapterB b;

    @Test
    void concurrentPublishSameIdSendsOnce() throws Exception {
        a.reset();
        b.reset();
        CountDownLatch release = new CountDownLatch(1);
        a.holdUntil(release);
        b.holdUntil(release);
        String id = "post-" + UUID.randomUUID();
        Post post = Post.builder(id).text("Hello").build();

        List<Thread> callers = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS, r -> {
            Thread t = new Thread(r, "caller-" + callers.size());
            callers.add(t);
            return t;
        });
        AtomicInteger entered = new AtomicInteger();
        try {
            List<Future<PublishResult>> calls = new ArrayList<>();
            for (int i = 0; i < CALLERS; i++) {
                calls.add(pool.submit(() -> {
                    entered.incrementAndGet();
                    return social.publish("alice", post);
                }));
            }
            // all 8 calls are inside publish before release: one holds both adapters, 7 wait for the post's lock
            awaitAllBlocked(callers, entered);
            release.countDown();

            int published = 0;
            int refused = 0;
            for (Future<PublishResult> call : calls) {
                try {
                    assertTrue(call.get(30, TimeUnit.SECONDS).allPublished());
                    published++;
                } catch (ExecutionException e) {
                    SocialException se = (SocialException) e.getCause();
                    assertEquals("post " + id + " is already published; use update", se.getMessage());
                    refused++;
                }
            }
            assertEquals(1, published);
            assertEquals(CALLERS - 1, refused);
            assertEquals(1, a.requests().size());
            assertEquals(1, b.requests().size());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void awaitAllBlocked(List<Thread> callers, AtomicInteger entered) throws InterruptedException {
        Set<Thread.State> blocked = Set.of(Thread.State.WAITING, Thread.State.TIMED_WAITING);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (entered.get() == CALLERS && callers.size() == CALLERS
                    && a.requests().size() == 1 && b.requests().size() == 1
                    && callers.stream().allMatch(t -> blocked.contains(t.getState()))) {
                return;
            }
            Thread.sleep(10);
        }
        fail("callers did not all block inside publish: entered=" + entered.get() + ", requests a="
                + a.requests().size() + " b=" + b.requests().size() + " "
                + callers.stream().map(t -> t.getName() + "=" + t.getState()).toList());
    }
}
