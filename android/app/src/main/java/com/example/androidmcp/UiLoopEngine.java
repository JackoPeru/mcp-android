package com.example.androidmcp;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Synchronization and bounded semantic navigation for agent-driven UI loops. */
public final class UiLoopEngine {
    private static final int DEFAULT_CONTEXT_NODES = 250;
    private static final long POLL_MS = 250;

    private final McpAccessibilityService service;
    private final ScreenSnapshotStore snapshots;

    public UiLoopEngine(McpAccessibilityService service, ScreenSnapshotStore snapshots) {
        this.service = service;
        this.snapshots = snapshots;
    }

    static boolean isIdle(long lastEventAgeMs, long quietMs, String previousHash, String currentHash) {
        return lastEventAgeMs >= quietMs && previousHash != null && previousHash.equals(currentHash);
    }

    static boolean shouldStopScroll(Set<String> seenHashes, String currentHash, int steps, int maxSteps) {
        return steps >= maxSteps || seenHashes.contains(currentHash);
    }

    public ScreenSnapshotStore.Snapshot capture() throws ApiException {
        return snapshots.capture(service.compactContext(false, DEFAULT_CONTEXT_NODES));
    }

    public JSONObject waitIdle(long timeoutMs, long quietMs) throws ApiException {
        validateWait(timeoutMs);
        if (quietMs < 100 || quietMs > 1_000) throw new ApiException("INVALID_ARGUMENT", "Invalid quiet window");
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        String previous = null;
        ScreenSnapshotStore.Snapshot current = null;
        for (;;) {
            RequestScope.checkCurrent();
            current = capture();
            long eventTime = EventJournal.lastEventTimeMs();
            long eventAge = eventTime <= 0 ? Long.MAX_VALUE
                    : Math.max(0, System.currentTimeMillis() - eventTime);
            if (isIdle(eventAge, quietMs, previous, current.uiHash)) {
                return waitResult("idle", started, current);
            }
            previous = current.uiHash;
            if (System.nanoTime() >= deadline) throw new ApiException("WAIT_TIMEOUT", "UI did not become idle");
            sleep(deadline);
        }
    }

    public JSONObject waitChange(long snapshotId, String uiHash, long timeoutMs) throws ApiException {
        validateWait(timeoutMs);
        String baseHash = uiHash == null ? "" : uiHash.trim();
        if (snapshotId > 0) baseHash = snapshots.get(snapshotId).uiHash;
        if (baseHash.isEmpty()) throw new ApiException("INVALID_ARGUMENT", "snapshotId or uiHash is required");
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (;;) {
            RequestScope.checkCurrent();
            ScreenSnapshotStore.Snapshot current = capture();
            if (!baseHash.equals(current.uiHash)) return waitResult("changed", started, current);
            if (System.nanoTime() >= deadline) throw new ApiException("WAIT_TIMEOUT", "UI did not change");
            sleep(deadline);
        }
    }

    public JSONObject waitActivity(String packageName, String windowClass, long timeoutMs) throws ApiException {
        validateWait(timeoutMs);
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (;;) {
            RequestScope.checkCurrent();
            ScreenSnapshotStore.Snapshot current = capture();
            String currentPackage = current.context.optString("packageName", "");
            String currentWindow = current.context.optString("windowClass", "");
            boolean matches = packageName.equals(currentPackage)
                    && (windowClass == null || windowClass.isEmpty() || windowClass.equals(currentWindow));
            if (matches) return waitResult("activity", started, current);
            if (System.nanoTime() >= deadline) throw new ApiException("WAIT_TIMEOUT", "Activity condition not reached");
            sleep(deadline);
        }
    }

    public JSONObject scrollTo(JSONObject selector, String direction, int maxSteps, long timeoutMs)
            throws ApiException {
        if (!Set.of("up", "down", "left", "right").contains(direction)
                || maxSteps < 1 || maxSteps > 12) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid scroll-to bounds");
        }
        validateWait(timeoutMs);
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Set<String> seen = new HashSet<>();
        int steps = 0;
        for (;;) {
            RequestScope.checkCurrent();
            JSONObject found = service.find(selector, 1);
            if (found.optInt("count", 0) > 0) {
                ScreenSnapshotStore.Snapshot current = capture();
                return scrollResult(true, "found", steps, started, current, found);
            }
            ScreenSnapshotStore.Snapshot before = capture();
            if (shouldStopScroll(seen, before.uiHash, steps, maxSteps)) {
                String reason = seen.contains(before.uiHash) ? "repeated_state" : "max_steps";
                return scrollResult(false, reason, steps, started, before, found);
            }
            seen.add(before.uiHash);
            service.scrollDirection(direction);
            steps++;
            ScreenSnapshotStore.Snapshot changed = waitForChangedHash(before.uiHash, deadline);
            if (changed == null) {
                return scrollResult(false, "end_reached", steps, started, before, found);
            }
            if (System.nanoTime() >= deadline) {
                return scrollResult(false, "timeout", steps, started, changed, found);
            }
        }
    }

    private ScreenSnapshotStore.Snapshot waitForChangedHash(String baseHash, long outerDeadline)
            throws ApiException {
        long localDeadline = Math.min(outerDeadline,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500));
        for (;;) {
            RequestScope.checkCurrent();
            ScreenSnapshotStore.Snapshot current = capture();
            if (!baseHash.equals(current.uiHash)) return current;
            if (System.nanoTime() >= localDeadline) return null;
            sleep(localDeadline);
        }
    }

    private static JSONObject waitResult(String condition, long started,
                                         ScreenSnapshotStore.Snapshot snapshot) throws ApiException {
        try {
            return new JSONObject()
                    .put("condition", condition)
                    .put("elapsedMs", elapsedMs(started))
                    .put("snapshotId", snapshot.id)
                    .put("uiHash", snapshot.uiHash);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode synchronization result");
        }
    }

    private static JSONObject scrollResult(boolean found, String reason, int steps, long started,
                                           ScreenSnapshotStore.Snapshot snapshot, JSONObject match)
            throws ApiException {
        try {
            return new JSONObject()
                    .put("found", found)
                    .put("reason", reason)
                    .put("steps", steps)
                    .put("elapsedMs", elapsedMs(started))
                    .put("snapshotId", snapshot.id)
                    .put("uiHash", snapshot.uiHash)
                    .put("match", found ? match : JSONObject.NULL);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode scroll result");
        }
    }

    private static void validateWait(long timeoutMs) throws ApiException {
        if (timeoutMs < 0 || timeoutMs > 12_000) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid synchronization timeout");
        }
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void sleep(long deadline) throws ApiException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadline - System.nanoTime()));
        if (remaining <= 0) return;
        try {
            Thread.sleep(Math.min(POLL_MS, Math.max(1, remaining)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("TIMEOUT", "UI synchronization interrupted");
        }
    }
}
