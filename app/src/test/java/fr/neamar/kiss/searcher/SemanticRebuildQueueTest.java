package fr.neamar.kiss.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemanticRebuildQueueTest {
    @Test void concurrentChangesDoNotCreateDuplicateWorkers() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService submitters = Executors.newFixedThreadPool(4);
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger built = new AtomicInteger();
        AtomicInteger last = new AtomicInteger(-1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        SemanticRebuildQueue queue = new SemanticRebuildQueue(task -> {
            scheduled.incrementAndGet(); worker.execute(task);
        });
        try {
            queue.submit(() -> {
                built.incrementAndGet(); started.countDown();
                try { release.await(); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int thread = 0; thread < 4; thread++) {
                submitters.submit(() -> {
                    for (int i = 0; i < 500; i++) queue.submit(built::incrementAndGet);
                });
            }
            submitters.shutdown();
            assertTrue(submitters.awaitTermination(5, TimeUnit.SECONDS));
            queue.submit(() -> { built.incrementAndGet(); last.set(99999); done.countDown(); });
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(2, built.get());
            assertEquals(99999, last.get());
            assertEquals(1, scheduled.get());
        } finally {
            release.countDown(); submitters.shutdownNow(); worker.shutdownNow();
        }
    }

    @Test void providerBurstsBuildOnlyTheLatestSnapshot() {
        Queue<Runnable> worker = new ArrayDeque<>();
        SemanticRebuildQueue queue = new SemanticRebuildQueue(worker::add);
        List<Integer> built = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final int generation = i;
            queue.submit(() -> built.add(generation));
        }
        assertEquals(1, worker.size());
        worker.remove().run();
        assertEquals(Arrays.asList(199), built);
        assertEquals(0, worker.size());
    }

    @Test void changesDuringBuildProduceOneLatestReplacement() {
        Queue<Runnable> worker = new ArrayDeque<>();
        SemanticRebuildQueue queue = new SemanticRebuildQueue(worker::add);
        List<Integer> built = new ArrayList<>();
        queue.submit(() -> {
            built.add(0);
            for (int i = 1; i <= 100; i++) {
                final int generation = i;
                queue.submit(() -> built.add(generation));
            }
            assertEquals(0, worker.size());
        });
        worker.remove().run();
        assertEquals(Arrays.asList(0, 100), built);
        assertEquals(0, worker.size());
    }

    @Test void failedWorkDoesNotLoseANewerRequest() {
        Queue<Runnable> worker = new ArrayDeque<>();
        SemanticRebuildQueue queue = new SemanticRebuildQueue(worker::add);
        List<Integer> built = new ArrayList<>();
        queue.submit(() -> {
            queue.submit(() -> built.add(1));
            throw new IllegalStateException("build failed");
        });
        assertThrows(IllegalStateException.class, () -> worker.remove().run());
        assertEquals(1, worker.size());
        worker.remove().run();
        assertEquals(Arrays.asList(1), built);
    }
}
