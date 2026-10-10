package fr.neamar.kiss.searcher;

import java.util.concurrent.Executor;

/** Keeps one running semantic build and only the latest replacement request. */
final class SemanticRebuildQueue {
    private final Executor executor;
    private Runnable pending;
    private boolean scheduled;

    SemanticRebuildQueue(Executor executor) {
        this.executor = executor;
    }

    synchronized void submit(Runnable build) {
        pending = build;
        if (scheduled) return;
        scheduled = true;
        executor.execute(this::drain);
    }

    private void drain() {
        try {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = pending;
                    pending = null;
                }
                if (next == null) return;
                next.run();
            }
        } finally {
            synchronized (this) {
                scheduled = false;
                if (pending != null) {
                    scheduled = true;
                    executor.execute(this::drain);
                }
            }
        }
    }
}
