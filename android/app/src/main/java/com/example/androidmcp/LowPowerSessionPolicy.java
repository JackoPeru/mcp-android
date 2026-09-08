package com.example.androidmcp;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Pure policy for minimizing background work outside an explicit remote-control session. */
public final class LowPowerSessionPolicy {
    private static final long NETWORK_WATCHDOG_MS = 15L * 60L * 1000L;

    private LowPowerSessionPolicy() { }

    static int accessibilityEventTypes(boolean sessionActive) {
        return sessionActive ? McpAccessibilityService.trackedEventTypes() : 0;
    }

    static boolean notificationListenerActive(boolean sessionActive) {
        return sessionActive;
    }

    static long networkWatchdogMs() {
        return NETWORK_WATCHDOG_MS;
    }

    static ThreadPoolExecutor createSingleIdleWorkerExecutor(String threadName) {
        return new ThreadPoolExecutor(
                0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
