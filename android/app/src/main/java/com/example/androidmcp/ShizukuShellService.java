package com.example.androidmcp;

import android.content.Context;
import android.system.Os;

import androidx.annotation.Keep;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku UserService. This process runs as Shizuku's own identity: normally
 * shell (UID 2000), or root only if the user explicitly started Shizuku as root.
 */
public final class ShizukuShellService extends IShizukuShellService.Stub {
    private static final int MAX_OUTPUT_BYTES = 200_000;

    public ShizukuShellService() { }

    @Keep
    public ShizukuShellService(Context context) { }

    @Override
    public synchronized String execute(String script, String stdin, String workdir, int timeoutMs) {
        JSONObject result = new JSONObject();
        Process process = null;
        ExecutorService readers = Executors.newFixedThreadPool(3);
        try {
            if (script == null || script.isEmpty() || script.length() > SecurityValidators.MAX_SHELL_INPUT
                    || stdin == null || stdin.length() > SecurityValidators.MAX_SHELL_INPUT
                    || workdir == null || workdir.length() > 1024
                    || timeoutMs < 250 || timeoutMs > 12_000) {
                return error("INVALID_ARGUMENT", "Invalid Shizuku shell request");
            }

            if (workdir.indexOf('\0') >= 0 || hasParentSegment(workdir)) {
                return error("INVALID_ARGUMENT", "Invalid Shizuku shell request");
            }
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", script);
            File directory = new File(workdir.isEmpty() ? "/data/local/tmp" : workdir).getCanonicalFile();
            if (!directory.isDirectory()) {
                return error("INVALID_WORKDIR", "Working directory does not exist");
            }
            builder.directory(directory);
            process = builder.start();

            final Process running = process;
            Future<Captured> stdout = readers.submit(() -> capture(running.getInputStream()));
            Future<Captured> stderr = readers.submit(() -> capture(running.getErrorStream()));
            Future<?> stdinWrite = readers.submit(() -> {
                try {
                    if (!stdin.isEmpty()) {
                        running.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
                    }
                    running.getOutputStream().close();
                } catch (IOException ignored) { }
            });
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            boolean finished = awaitProcessAndStdin(process, stdinWrite, deadline);
            Captured out = new Captured("", 0, false);
            Captured err = new Captured("", 0, false);
            if (finished) {
                try {
                    out = getBefore(stdout, deadline);
                    err = getBefore(stderr, deadline);
                } catch (TimeoutException e) {
                    finished = false;
                }
            }
            if (!finished) {
                stdinWrite.cancel(true);
                process.destroy();
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                stdout.cancel(true);
                stderr.cancel(true);
                result.put("timedOut", true);
                result.put("outcomeUnknown", true);
            } else {
                result.put("timedOut", false);
                result.put("outcomeUnknown", false);
            }
            result.put("stdout", out.text);
            result.put("stderr", err.text);
            result.put("stdoutBytes", out.bytes);
            result.put("stderrBytes", err.bytes);
            result.put("truncated", out.truncated || err.truncated);
            result.put("exitCode", finished ? process.exitValue() : JSONObject.NULL);
            result.put("uid", Os.getuid());
            result.put("gid", Os.getgid());
            result.put("backend", Os.getuid() == 0 ? "shizuku-root" : "shizuku-shell");
            return result.toString();
        } catch (Exception e) {
            return error("SHIZUKU_EXEC_FAILED", "Privileged command failed");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            readers.shutdownNow();
        }
    }

    static boolean awaitProcessAndStdin(Process process, Future<?> stdinWrite, long deadlineNanos)
            throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) return false;
        remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return false;
        try {
            stdinWrite.get(remaining, TimeUnit.NANOSECONDS);
            return true;
        } catch (ExecutionException e) {
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    private static <T> T getBefore(Future<T> future, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException();
        return future.get(remaining, TimeUnit.NANOSECONDS);
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    private static Captured capture(InputStream input) throws IOException {
        byte[] buffer = new byte[8_192];
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        long total = 0;
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            int remaining = MAX_OUTPUT_BYTES - kept.size();
            if (remaining > 0) kept.write(buffer, 0, Math.min(read, remaining));
            if (read > remaining) truncated = true;
        }
        return new Captured(kept.toString("UTF-8"), total, truncated);
    }

    static boolean hasParentSegment(String value) {
        for (String part : value.split("/")) {
            if ("..".equals(part)) return true;
        }
        return false;
    }

    private static String error(String code, String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("error", code);
            result.put("message", message);
        } catch (JSONException ignored) { }
        return result.toString();
    }

    private static final class Captured {
        final String text;
        final long bytes;
        final boolean truncated;
        Captured(String text, long bytes, boolean truncated) {
            this.text = text;
            this.bytes = bytes;
            this.truncated = truncated;
        }
    }
}
