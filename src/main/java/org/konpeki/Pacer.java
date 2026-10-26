package org.konpeki;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class Pacer {
    private static final Semaphore ticks = new Semaphore(0);
    private static volatile long lastTick;
    // Smoothed time between ticks, the display's refresh interval
    private static volatile long refresh = 16_666_667;

    private long owed;
    private long lastWake = System.nanoTime();
    private long due = System.nanoTime();

    static void tick() {
        long now = System.nanoTime();
        long gap = now - lastTick;
        if (gap < 100_000_000) refresh = (refresh * 7 + gap) / 8;
        lastTick = now;
        ticks.release();
    }

    // Waits until the next frame should be drawn
    void await(long periodNanos) throws InterruptedException {
        long period = Math.max(periodNanos, 1_000_000);
        while (true) {
            long now = System.nanoTime();
            if (now - lastTick < 100_000_000) {
                if (!ticks.tryAcquire(2 * period, TimeUnit.NANOSECONDS)) continue;
                long tick = System.nanoTime();
                owed += tick - lastWake;
                lastWake = tick;
                if (owed >= period - refresh / 2) {
                    // Don't try to catch up after a long gap
                    owed = Math.min(owed - period, period);
                    due = tick + period;
                    return;
                }
                continue;
            }
            // Not ticking, keep the sketch's own frame rate
            if (now >= due) {
                due = now - due > period ? now + period : due + period;
                lastWake = now;
                owed = 0;
                return;
            }
            // Returns early if the preview starts ticking again
            ticks.tryAcquire(due - now, TimeUnit.NANOSECONDS);
        }
    }
}
