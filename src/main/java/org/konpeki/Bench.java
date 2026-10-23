package org.konpeki;

import java.time.Duration;
import java.time.Instant;

public class Bench {
    public static final boolean ENABLED = System.getenv("PCLI_BENCH") != null;
    private static final Instant START = ProcessHandle.current().info().startInstant().orElse(Instant.now());

    public static void mark(String label) {
        if (ENABLED)
            System.err.printf("%-12s %6d ms%n", label, Duration.between(START, Instant.now()).toMillis());
    }

    public static final class FirstFrame {
        public void post() {
            mark("first-frame");
            System.exit(0);
        }
    }
}
