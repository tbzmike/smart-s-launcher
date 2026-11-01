package fr.neamar.kiss.utils;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class LauncherScrollWorkGateTest {
    @Test void parksWithoutPollingAndWakesForIdle() throws Exception {
        LauncherScrollWorkGate.setScrolling(true);
        AtomicBoolean completed = new AtomicBoolean();
        Thread worker = new Thread(() -> completed.set(LauncherScrollWorkGate.awaitIdle()));
        try {
            worker.start();
            for (int i = 0; i < 100 && worker.getState() != Thread.State.WAITING; i++) Thread.sleep(5L);
            assertEquals(Thread.State.WAITING, worker.getState());
            assertFalse(completed.get());
            LauncherScrollWorkGate.setScrolling(false);
            worker.join(1000L);
            assertTrue(completed.get());
        } finally { LauncherScrollWorkGate.setScrolling(false); worker.interrupt(); worker.join(1000L); }
    }
    @Test void disablingWorkWakesTheParkedWorkerWithoutWaitingForScroll() throws Exception {
        LauncherScrollWorkGate.setScrolling(true);
        AtomicBoolean allowed = new AtomicBoolean(true);
        AtomicBoolean accepted = new AtomicBoolean(true);
        Thread worker = new Thread(() -> accepted.set(LauncherScrollWorkGate.awaitIdle(allowed::get)));
        try {
            worker.start();
            for (int i = 0; i < 100 && worker.getState() != Thread.State.WAITING; i++) Thread.sleep(5L);
            allowed.set(false);
            LauncherScrollWorkGate.signalPolicyChanged();
            worker.join(1000L);
            assertFalse(worker.isAlive());
            assertFalse(accepted.get());
        } finally { LauncherScrollWorkGate.setScrolling(false); worker.interrupt(); worker.join(1000L); }
    }
}
