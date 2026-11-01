package fr.neamar.kiss.utils;

import android.os.Process;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serial optional work keeps its ordering, but idle workers release their native stacks. */
public final class IdleExecutors {
    private IdleExecutors() { }

    public static ThreadPoolExecutor single(String name) {
        return new ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), task -> {
                    Thread thread = new Thread(() -> {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        task.run();
                    }, name);
                    return thread;
                });
    }
}
