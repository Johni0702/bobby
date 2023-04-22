package de.johni0702.minecraft.bobby.util;

import org.jetbrains.annotations.NotNull;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

public class LimitedExecutor implements Executor, Runnable {

    private final Executor inner;
    private final int maxConcurrency;
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private int activeWorkers;

    public LimitedExecutor(Executor inner, int maxConcurrency) {
        this.inner = inner;
        this.maxConcurrency = maxConcurrency;
    }

    @Override
    public void execute(@NotNull Runnable runnable) {
        queue.add(runnable);

        synchronized (lock) {
            if (activeWorkers < maxConcurrency) {
                activeWorkers++;
                inner.execute(this);
            }
        }
    }

    @Override
    public void run() {
        while (true) {
            Runnable runnable = queue.poll();
            if (runnable != null) {
                runnable.run();
                continue;
            }

            synchronized (lock) {
                if (queue.isEmpty()) {
                    activeWorkers--;
                    return;
                }
            }
        }
    }
}
