package com.example.androidmcp;

import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Screen recording state machine. MediaProjection always requires the system
 * consent dialog, so start goes through {@link ScreenRecordActivity}; the
 * service runs as a mediaProjection foreground service and is fully visible.
 */
public final class ScreenRecordController {
    public static final Object LOCK = new Object();
    static volatile String state = "idle"; // idle | consent | recording | stopping | interrupted
    static volatile String fileName = "";
    static volatile String error = "";
    static volatile long startedAt;
    static volatile long maxDurationMs;
    static volatile int width, height, fps, bitrate;

    private ScreenRecordController() { }

    public static JSONObject start(Context context, int maxDurationSec, int requestedFps,
                                   int requestedBitrateKbps, int maxLongEdge,
                                   long timeoutMs) throws ApiException {
        if (maxDurationSec < 1 || maxDurationSec > 1800) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid max duration");
        }
        if (requestedFps < 10 || requestedFps > 60) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid fps");
        }
        if (requestedBitrateKbps < 500 || requestedBitrateKbps > 20_000) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid bitrate");
        }
        synchronized (LOCK) {
            if ("recording".equals(state) || "consent".equals(state)) {
                throw new ApiException("BUSY", "A screen recording is already active");
            }
            CaptureStore.cleanup(context);
            state = "consent";
            error = "";
            fileName = "";
            startedAt = 0;
        }
        try {
            context.startActivity(new Intent(context, ScreenRecordActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("maxDurationMs", maxDurationSec * 1000L)
                    .putExtra("fps", requestedFps)
                    .putExtra("bitrate", requestedBitrateKbps * 1000)
                    .putExtra("maxLongEdge", maxLongEdge));
        } catch (RuntimeException e) {
            synchronized (LOCK) { state = "idle"; }
            throw new ApiException("SCREEN_RECORD_UNAVAILABLE", "Unable to open the consent activity");
        }
        long deadline = System.currentTimeMillis() + Math.max(2000, timeoutMs);
        synchronized (LOCK) {
            try {
                while ("consent".equals(state) || "starting".equals(state)) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    LOCK.wait(Math.min(remaining, 500));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if ("recording".equals(state)) return statusObject();
            String failure = error.isEmpty() ? "SCREEN_RECORD_TIMEOUT" : error;
            if ("consent".equals(state) || "starting".equals(state)) state = "idle";
            throw new ApiException(failure, "Screen recording did not start");
        }
    }

    public static JSONObject stop(Context context) throws ApiException {
        synchronized (LOCK) {
            if (!"recording".equals(state)) return statusObject();
            state = "stopping";
        }
        Context app = context.getApplicationContext();
        app.startService(new Intent(app, ScreenRecordService.class).setAction("STOP"));
        long deadline = System.currentTimeMillis() + 15_000;
        synchronized (LOCK) {
            try {
                while ("stopping".equals(state)) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    LOCK.wait(Math.min(remaining, 500));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if ("stopping".equals(state)) {
                throw new ApiException("SCREEN_RECORD_STOP_TIMEOUT", "Recording still stopping; check status");
            }
            return statusObject();
        }
    }

    public static JSONObject status(Context context) throws ApiException {
        synchronized (LOCK) { return statusObject(); }
    }

    public static void forceStop(Context context) {
        synchronized (LOCK) {
            if (!"recording".equals(state)) return;
            state = "stopping";
        }
        try {
            context.getApplicationContext().startService(
                    new Intent(context.getApplicationContext(), ScreenRecordService.class)
                            .setAction("STOP"));
        } catch (RuntimeException ignored) { }
    }

    private static JSONObject statusObject() throws ApiException {
        try {
            return new JSONObject()
                    .put("state", state)
                    .put("recording", "recording".equals(state))
                    .put("name", fileName)
                    .put("mimeType", "video/mp4")
                    .put("durationMs", startedAt > 0 && "recording".equals(state)
                            ? Math.max(0, System.currentTimeMillis() - startedAt) : 0)
                    .put("maxDurationMs", maxDurationMs)
                    .put("width", width)
                    .put("height", height)
                    .put("fps", fps)
                    .put("bitrate", bitrate)
                    .put("error", error);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode recording state");
        }
    }

    static void onStarted(String name, int w, int h, int frameRate, int bitRate, long maxDuration) {
        synchronized (LOCK) {
            state = "recording";
            fileName = name;
            width = w; height = h; fps = frameRate; bitrate = bitRate;
            maxDurationMs = maxDuration;
            startedAt = System.currentTimeMillis();
            error = "";
            LOCK.notifyAll();
        }
    }

    static void onFinished(boolean interrupted, String failure) {
        synchronized (LOCK) {
            state = interrupted ? "interrupted" : "idle";
            if (failure != null && !failure.isEmpty()) error = failure;
            LOCK.notifyAll();
        }
    }

    static void onError(String code) {
        synchronized (LOCK) {
            state = "idle";
            error = code;
            LOCK.notifyAll();
        }
    }
}
