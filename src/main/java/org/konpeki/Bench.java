package org.konpeki;

import java.time.Duration;
import java.time.Instant;

public class Bench {
    private static final Instant START =
            ProcessHandle.current().info().startInstant().orElse(Instant.now());

    private static boolean enabled = System.getenv("PCLI_BENCH") != null;

    public static void enable() {
        enabled = true;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void mark(String label) {
        if (enabled) {
            System.err.printf(
                    "%-12s %6d ms%n",
                    label, Duration.between(START, Instant.now()).toMillis());
        }
    }

    public static final class FirstFrame {
        public void post() {
            mark("first-frame");
            System.exit(0);
        }
    }
}
