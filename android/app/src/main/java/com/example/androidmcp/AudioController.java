package com.example.androidmcp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;

/**
 * Microphone recording state machine shared by the RPC thread, the visible
 * capture activity and the foreground service.
 *
 * <p>Recording is never invisible: the activity shows the live state and a
 * Stop button, and the service posts an ongoing notification with a Stop
 * action. All transitions happen here so the agent always sees the real state.
 */
public final class AudioController {
    public static final Object LOCK = new Object();
    static volatile String state = "idle"; // idle | starting | recording | stopping | interrupted
    static volatile String fileName;
    static volatile String error = "";
    static volatile long startedAt;
    static volatile long maxDurationMs;
    static volatile boolean stopRequested;

    private AudioController() { }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    public static JSONObject start(Context context, String format, int maxDurationSec,
                                   int bitrateKbps, long timeoutMs) throws ApiException {
        if (!hasPermission(context)) {
            throw new ApiException("MIC_PERMISSION_DENIED", "Grant the microphone permission in MCP Android");
        }
        String ext = "opus".equals(format) || "ogg".equals(format) ? "ogg" : "m4a";
        if (!Arrays.asList("m4a", "aac", "ogg", "opus").contains(format)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid audio format");
        }
        if (maxDurationSec < 1 || maxDurationSec > 3600) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid max duration");
        }
        if (bitrateKbps < 16 || bitrateKbps > 320) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid bitrate");
        }
        synchronized (LOCK) {
            if ("recording".equals(state) || "starting".equals(state)) {
                throw new ApiException("BUSY", "A recording is already active");
            }
            CaptureStore.cleanup(context);
            state = "starting";
            error = "";
            fileName = null;
            startedAt = 0;
            stopRequested = false;
            maxDurationMs = maxDurationSec * 1000L;
        }
        try {
            context.startActivity(new Intent(context, AudioCaptureActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("ext", ext)
                    .putExtra("maxDurationMs", maxDurationSec * 1000L)
                    .putExtra("bitrate", bitrateKbps * 1000));
        } catch (RuntimeException e) {
            synchronized (LOCK) { state = "idle"; }
            throw new ApiException("MIC_UNAVAILABLE", "Unable to open the recording activity");
        }
        long deadline = System.currentTimeMillis() + Math.max(2000, timeoutMs);
        synchronized (LOCK) {
            try {
                while (!"recording".equals(state) && !"idle".equals(state) && !"interrupted".equals(state)) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    LOCK.wait(Math.min(remaining, 500));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if ("recording".equals(state)) return statusObject();
            String failure = error.isEmpty() ? "MIC_RECORD_START_FAILED" : error;
            state = "idle";
            throw new ApiException(failure, "Recording did not start");
        }
    }

    public static JSONObject stop(Context context) throws ApiException {
        synchronized (LOCK) {
            if (!"recording".equals(state) && !"starting".equals(state)) {
                return statusObject();
            }
            stopRequested = true;
            state = "stopping";
            LOCK.notifyAll();
        }
        Context app = context.getApplicationContext();
        app.startService(new Intent(app, AudioCaptureService.class).setAction("STOP"));
        long deadline = System.currentTimeMillis() + 12_000;
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
                throw new ApiException("MIC_STOP_TIMEOUT", "Recording still stopping; check status");
            }
            return statusObject();
        }
    }

    public static JSONObject status(Context context) throws ApiException {
        synchronized (LOCK) {
            return statusObject();
        }
    }

    public static void forceStop(Context context) {
        synchronized (LOCK) {
            if ("recording".equals(state) || "starting".equals(state)) {
                stopRequested = true;
                state = "stopping";
            }
        }
        try {
            context.getApplicationContext().startService(
                    new Intent(context.getApplicationContext(), AudioCaptureService.class)
                            .setAction("STOP"));
        } catch (RuntimeException ignored) { }
    }

    private static JSONObject statusObject() throws ApiException {
        try {
            JSONObject result = new JSONObject();
            result.put("state", state);
            result.put("permission", true);
            result.put("recording", "recording".equals(state));
            result.put("name", fileName == null ? "" : fileName);
            result.put("mimeType", fileName == null ? "" : CaptureStore.mimeType(fileName));
            result.put("durationMs", startedAt <= 0 || !"recording".equals(state)
                    ? 0 : Math.max(0, System.currentTimeMillis() - startedAt));
            result.put("maxDurationMs", maxDurationMs);
            result.put("error", error);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode recording state");
        }
    }

    static void onStarted(File file, long maxDuration) {
        synchronized (LOCK) {
            state = "recording";
            fileName = file.getName();
            startedAt = System.currentTimeMillis();
            maxDurationMs = maxDuration;
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
