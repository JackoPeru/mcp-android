package com.example.androidmcp;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional shell backend through Termux's documented RUN_COMMAND Intent API. */
public final class TermuxBridge {
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION = "com.termux.RUN_COMMAND";
    private static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_PENDING = "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String BASH = "$PREFIX/bin/bash";
    private static final String HOME = "~/";
    private static final AtomicInteger NEXT = new AtomicInteger(1000);
    private static final ConcurrentHashMap<Integer, CompletableFuture<Result>> WAITING = new ConcurrentHashMap<>();

    private TermuxBridge() { }

    public static JSONObject status(Context context) throws ApiException {
        JSONObject result = new JSONObject();
        try {
            boolean installed;
            try { context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0); installed = true; }
            catch (PackageManager.NameNotFoundException e) { installed = false; }
            result.put("installed", installed);
            result.put("permission",
                    context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED);
            result.put("backend", "Termux RUN_COMMAND");
            result.put("supportsOutput", true);
            result.put("maxScriptBytes", SecurityValidators.MAX_SHELL_INPUT);
            result.put("maxTimeoutMs", 12000);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode shell status");
        }
    }

    public static JSONObject execute(Context context, String script, String stdin, String workdir, long timeoutMs)
            throws ApiException {
        if (script == null || script.isEmpty() || script.length() > SecurityValidators.MAX_SHELL_INPUT
                || stdin == null || stdin.length() > SecurityValidators.MAX_SHELL_INPUT
                || workdir == null || workdir.length() > 1024 || workdir.indexOf('\0') >= 0
                || timeoutMs < 250 || timeoutMs > 12_000) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid shell request");
        }
        try { context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0); }
        catch (PackageManager.NameNotFoundException e) { throw new ApiException("TERMUX_UNAVAILABLE", "Termux is not installed"); }
        if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED)
            throw new ApiException("TERMUX_PERMISSION_REQUIRED", "Grant Run commands in Termux environment");

        int id = NEXT.updateAndGet(value -> value == Integer.MAX_VALUE ? 1000 : value + 1);
        CompletableFuture<Result> future = new CompletableFuture<>();
        WAITING.put(id, future);
        Intent callback = new Intent(context, TermuxResultService.class).putExtra("requestId", id);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getService(context, id, callback, flags);

        Intent command = new Intent();
        command.setClassName(TERMUX_PACKAGE, TERMUX_SERVICE);
        command.setAction(ACTION);
        command.putExtra(EXTRA_PATH, BASH);
        command.putExtra(EXTRA_ARGS, new String[]{"-lc", script});
        command.putExtra(EXTRA_STDIN, stdin);
        command.putExtra(EXTRA_WORKDIR, workdir.isEmpty() ? HOME : workdir);
        command.putExtra(EXTRA_BACKGROUND, true);
        command.putExtra(EXTRA_PENDING, pending);
        try {
            context.startService(command);
        } catch (SecurityException e) {
            WAITING.remove(id);
            throw new ApiException("TERMUX_PERMISSION_REQUIRED", "Termux RUN_COMMAND permission denied");
        } catch (RuntimeException e) {
            WAITING.remove(id);
            throw new ApiException("TERMUX_UNAVAILABLE", "Unable to start Termux command");
        }

        try {
            Result result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            RequestScope.checkCurrent();
            return result.json();
        } catch (java.util.concurrent.TimeoutException e) {
            throw new ApiException("TIMEOUT", "Termux command timed out; process outcome may be unknown");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("TIMEOUT", "Termux command interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new ApiException("TERMUX_FAILED", "Termux command result failed");
        } finally {
            WAITING.remove(id);
        }
    }

    static void acceptResult(Intent intent) {
        if (intent == null) return;
        int id = intent.getIntExtra("requestId", -1);
        CompletableFuture<Result> future = WAITING.get(id);
        if (future == null) return;
        try { future.complete(parse(intent)); }
        catch (RuntimeException e) { future.completeExceptionally(e); }
    }

    private static Result parse(Intent intent) {
        Bundle extras = intent.getExtras();
        Bundle payload = null;
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value instanceof Bundle) {
                    payload = (Bundle) value;
                    break;
                }
            }
        }
        if (payload == null) payload = extras == null ? new Bundle() : extras;
        String stdout = "";
        String stderr = "";
        String errorMessage = "";
        int exitCode = Integer.MIN_VALUE;
        int errorCode = 0;
        int stdoutOriginal = -1;
        int stderrOriginal = -1;
        for (String key : payload.keySet()) {
            Object value = payload.get(key);
            String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
            if (normalized.contains("stdout") && normalized.contains("original")) {
                stdoutOriginal = intValue(value, -1);
            } else if (normalized.contains("stderr") && normalized.contains("original")) {
                stderrOriginal = intValue(value, -1);
            } else if (normalized.endsWith("stdout") || normalized.equals("stdout")) {
                stdout = stringValue(value);
            } else if (normalized.endsWith("stderr") || normalized.equals("stderr")) {
                stderr = stringValue(value);
            } else if (normalized.contains("exit") && normalized.contains("code")) {
                exitCode = intValue(value, Integer.MIN_VALUE);
            } else if (normalized.endsWith("errmsg") || normalized.contains("error_message")) {
                errorMessage = stringValue(value);
            } else if (normalized.endsWith("_err") || normalized.equals("err")) {
                errorCode = intValue(value, 0);
            }
        }
        return new Result(cap(stdout), cap(stderr), cap(errorMessage), exitCode, errorCode,
                stdoutOriginal, stderrOriginal);
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
    private static String stringValue(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String cap(String value) {
        if (value == null) return "";
        return value.length() <= 200_000 ? value : value.substring(0, 200_000);
    }

    private static final class Result {
        final String stdout, stderr, errorMessage;
        final int exitCode, errorCode, stdoutOriginal, stderrOriginal;
        Result(String stdout, String stderr, String errorMessage, int exitCode, int errorCode,
               int stdoutOriginal, int stderrOriginal) {
            this.stdout = stdout; this.stderr = stderr; this.errorMessage = errorMessage;
            this.exitCode = exitCode; this.errorCode = errorCode;
            this.stdoutOriginal = stdoutOriginal; this.stderrOriginal = stderrOriginal;
        }
        JSONObject json() throws ApiException {
            JSONObject result = new JSONObject();
            try {
                result.put("stdout", stdout);
                result.put("stderr", stderr);
                result.put("exitCode", exitCode == Integer.MIN_VALUE ? JSONObject.NULL : exitCode);
                result.put("termuxErrorCode", errorCode);
                result.put("errorMessage", errorMessage);
                result.put("stdoutOriginalLength", stdoutOriginal);
                result.put("stderrOriginalLength", stderrOriginal);
                result.put("truncated",
                        (stdoutOriginal >= 0 && stdoutOriginal > stdout.length())
                        || (stderrOriginal >= 0 && stderrOriginal > stderr.length()));
                return result;
            } catch (JSONException e) {
                throw new ApiException("INTERNAL", "Unable to encode shell result");
            }
        }
    }
}
