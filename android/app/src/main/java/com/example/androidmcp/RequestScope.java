package com.example.androidmcp;

import android.os.CancellationSignal;
import java.io.Closeable;
import java.io.IOException;

/** One request's deadline and cancellation, including provider reads and queued UI work. */
final class RequestScope {
    static final ThreadLocal<RequestScope> CURRENT = new ThreadLocal<>();
    static final long DEFAULT_TIMEOUT_MS = 20_000L;
    private volatile CancellationSignal signal;
    private final long deadline;
    private volatile boolean cancelled;
    private Closeable resource;

    RequestScope() {
        this(DEFAULT_TIMEOUT_MS);
    }

    RequestScope(long timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }

    void check() throws ApiException {
        if (cancelled || System.nanoTime() >= deadline) throw new ApiException("TIMEOUT", "Request cancelled or expired");
    }

    synchronized void beginAction(java.util.concurrent.atomic.AtomicBoolean busy) throws ApiException {
        check();
        if (!busy.compareAndSet(false, true)) throw new ApiException("BUSY", "Native operation still in flight");
    }

    synchronized CancellationSignal signal() {
        if (signal == null) signal = new CancellationSignal();
        if (cancelled) signal.cancel();
        return signal;
    }

    synchronized void attach(Closeable next) throws ApiException {
        if (cancelled || System.nanoTime() >= deadline) {
            close(next);
            throw new ApiException("TIMEOUT", "Request cancelled or expired");
        }
        resource = next;
    }

    void cancel() {
        synchronized (this) {
            cancelled = true;
            close(resource);
            resource = null;
        }
        CancellationSignal current = signal;
        if (current != null) current.cancel();
    }

    static void checkCurrent() throws ApiException {
        RequestScope scope = CURRENT.get();
        if (scope != null) scope.check();
    }

    private static void close(Closeable value) {
        if (value != null) try { value.close(); } catch (IOException ignored) { }
    }
}
