package com.example.androidmcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/** Small in-memory event feed. It stores metadata only, never accessibility text or notification bodies. */
public final class EventJournal {
    private static final int MAX_EVENTS = 256;
    private static final AtomicLong NEXT = new AtomicLong(1);
    private static final ArrayDeque<Entry> EVENTS = new ArrayDeque<>();

    private EventJournal() { }

    public static synchronized void add(String type, String packageName, String detail) {
        long id = NEXT.getAndIncrement();
        EVENTS.addLast(new Entry(id, System.currentTimeMillis(), safe(type, 80),
                safe(packageName, 200), safe(detail, 200)));
        while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
        EventJournal.class.notifyAll();
    }

    public static synchronized JSONObject since(long afterId, int limit) throws ApiException {
        if (afterId < 0 || limit < 1 || limit > 200) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid event cursor");
        }
        JSONArray items = new JSONArray();
        long newest = afterId;
        try {
            for (Entry entry : EVENTS) {
                if (entry.id <= afterId) continue;
                JSONObject item = new JSONObject();
                item.put("id", entry.id);
                item.put("time", entry.time);
                item.put("type", entry.type);
                item.put("packageName", entry.packageName);
                item.put("detail", entry.detail);
                items.put(item);
                newest = entry.id;
                if (items.length() >= limit) break;
            }
            JSONObject result = new JSONObject();
            result.put("events", items);
            result.put("nextAfterId", newest);
            result.put("latestId", Math.max(0, NEXT.get() - 1));
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode events");
        }
    }

    public static JSONObject waitSince(long afterId, int limit, long timeoutMs) throws ApiException {
        if (afterId < 0 || limit < 1 || limit > 200 || timeoutMs < 0 || timeoutMs > 12_000) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid event wait");
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (EventJournal.class) {
            for (;;) {
                RequestScope.checkCurrent();
                if (NEXT.get() - 1 > afterId || timeoutMs == 0) {
                    JSONObject result = since(afterId, limit);
                    try { result.put("timedOut", false); }
                    catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to encode events"); }
                    return result;
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    JSONObject result = since(afterId, limit);
                    try { result.put("timedOut", true); }
                    catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to encode events"); }
                    return result;
                }
                long waitMs = Math.min(250,
                        Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                try { EventJournal.class.wait(waitMs); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ApiException("TIMEOUT", "Event wait interrupted");
                }
            }
        }
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class Entry {
        final long id;
        final long time;
        final String type;
        final String packageName;
        final String detail;
        Entry(long id, long time, String type, String packageName, String detail) {
            this.id = id;
            this.time = time;
            this.type = type;
            this.packageName = packageName;
            this.detail = detail;
        }
    }
}
