package com.example.androidmcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded metadata-only execution trace journal. Never stores action parameters or payload content. */
public final class TraceJournal {
    private static final int MAX_TRACES = 128;
    private static final ArrayDeque<Entry> TRACES = new ArrayDeque<>();
    private static final AtomicLong NEXT = new AtomicLong(1);
    private static long dropped;

    private TraceJournal() { }

    public static synchronized void add(String kind, String operation, long durationMs,
                                        String status, String error) {
        Entry entry = new Entry(
                NEXT.getAndIncrement(),
                System.currentTimeMillis(),
                safe(kind, 40),
                safe(operation, 80),
                Math.max(0, durationMs),
                safe(status, 24),
                safe(error, 80));
        TRACES.addLast(entry);
        while (TRACES.size() > MAX_TRACES) {
            TRACES.removeFirst();
            dropped++;
        }
    }

    public static synchronized JSONObject recent(int limit) throws ApiException {
        if (limit < 1 || limit > 200) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid trace limit");
        }
        int skip = Math.max(0, TRACES.size() - limit);
        int index = 0;
        JSONArray items = new JSONArray();
        try {
            for (Entry entry : TRACES) {
                if (index++ < skip) continue;
                items.put(new JSONObject()
                        .put("id", entry.id)
                        .put("time", entry.time)
                        .put("kind", entry.kind)
                        .put("operation", entry.operation)
                        .put("durationMs", entry.durationMs)
                        .put("status", entry.status)
                        .put("error", entry.error));
            }
            return new JSONObject()
                    .put("traces", items)
                    .put("count", items.length())
                    .put("retained", TRACES.size())
                    .put("dropped", dropped)
                    .put("truncated", dropped > 0 || TRACES.size() > limit)
                    .put("latestId", Math.max(0, NEXT.get() - 1));
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode traces");
        }
    }

    static synchronized void clearForTests() {
        TRACES.clear();
        NEXT.set(1);
        dropped = 0;
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String sanitized = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return sanitized.length() <= max ? sanitized : sanitized.substring(0, max);
    }

    private static final class Entry {
        final long id;
        final long time;
        final String kind;
        final String operation;
        final long durationMs;
        final String status;
        final String error;

        Entry(long id, long time, String kind, String operation,
              long durationMs, String status, String error) {
            this.id = id;
            this.time = time;
            this.kind = kind;
            this.operation = operation;
            this.durationMs = durationMs;
            this.status = status;
            this.error = error;
        }
    }
}
