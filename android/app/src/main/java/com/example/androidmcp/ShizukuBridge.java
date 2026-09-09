package com.example.androidmcp;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/** Explicitly-consented optional Shizuku backend. Never used as an implicit fallback. */
public final class ShizukuBridge {
    public static final int PERMISSION_REQUEST = 41;
    private static final Object LOCK = new Object();
    private static volatile Context appContext;
    private static volatile IShizukuShellService service;
    private static volatile CompletableFuture<IShizukuShellService> binding;
    private static volatile Shizuku.UserServiceArgs serviceArgs;

    private static final Shizuku.OnBinderDeadListener BINDER_DEAD = () -> {
        service = null;
        CompletableFuture<IShizukuShellService> pending = binding;
        if (pending != null && !pending.isDone()) {
            pending.completeExceptionally(new IllegalStateException("Shizuku binder died"));
        }
    };

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            IShizukuShellService connected = IShizukuShellService.Stub.asInterface(binder);
            service = connected;
            CompletableFuture<IShizukuShellService> pending = binding;
            if (pending != null) pending.complete(connected);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private ShizukuBridge() { }

    public static void initialize(Context context) {
        if (appContext != null) return;
        synchronized (LOCK) {
            if (appContext != null) return;
            appContext = context.getApplicationContext();
            Shizuku.addBinderDeadListener(BINDER_DEAD);
            serviceArgs = new Shizuku.UserServiceArgs(
                    new ComponentName(appContext, ShizukuShellService.class))
                    .tag("mcp-android-shizuku-shell")
                    .version(1)
                    .daemon(false);
        }
    }

    public static JSONObject status(Context context) throws ApiException {
        initialize(context);
        JSONObject result = new JSONObject();
        try {
            boolean alive = safePing();
            result.put("binderAlive", alive);
            result.put("permission", alive && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);
            result.put("preV11", alive && Shizuku.isPreV11());
            if (alive) {
                int uid = Shizuku.getUid();
                result.put("serverUid", uid);
                result.put("serverMode", uid == 0 ? "root" : uid == 2000 ? "shell" : "other");
                result.put("serverVersion", Shizuku.getVersion());
            } else {
                result.put("serverUid", JSONObject.NULL);
                result.put("serverMode", "unavailable");
            }
            IShizukuShellService current = service;
            result.put("userServiceBound", current != null && current.asBinder().pingBinder());
            result.put("explicitOnly", true);
            return result;
        } catch (IllegalStateException | JSONException e) {
            throw new ApiException("SHIZUKU_UNAVAILABLE", "Shizuku is unavailable");
        }
    }

    public static boolean requestPermission(Context context) throws ApiException {
        initialize(context);
        if (!safePing()) throw new ApiException("SHIZUKU_UNAVAILABLE", "Start Shizuku first");
        if (Shizuku.isPreV11()) throw new ApiException("SHIZUKU_UNSUPPORTED", "Shizuku v11 or newer is required");
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            throw new ApiException("SHIZUKU_PERMISSION_DENIED", "Enable MCP Android in Shizuku");
        }
        Shizuku.requestPermission(PERMISSION_REQUEST);
        return false;
    }

    public static JSONObject execute(Context context, String script, String stdin, String workdir, long timeoutMs)
            throws ApiException {
        initialize(context);
        if (script == null || script.isEmpty() || script.length() > SecurityValidators.MAX_SHELL_INPUT
                || stdin == null || stdin.length() > SecurityValidators.MAX_SHELL_INPUT
                || workdir == null || workdir.length() > 1024
                || timeoutMs < 250 || timeoutMs > 12_000) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid Shizuku shell request");
        }
        if (!safePing()) throw new ApiException("SHIZUKU_UNAVAILABLE", "Start Shizuku first");
        if (Shizuku.isPreV11()) throw new ApiException("SHIZUKU_UNSUPPORTED", "Shizuku v11 or newer is required");
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            throw new ApiException("SHIZUKU_PERMISSION_REQUIRED", "Grant MCP Android access in Shizuku");

        IShizukuShellService remote = ensureBound();
        try {
            String raw = remote.execute(script, stdin, workdir, (int) timeoutMs);
            JSONObject result = new JSONObject(raw);
            if (result.has("error")) {
                String code = result.optString("error", "SHIZUKU_EXEC_FAILED");
                throw new ApiException(code, "Shizuku command failed");
            }
            return result;
        } catch (RemoteException e) {
            service = null;
            throw new ApiException("SHIZUKU_DISCONNECTED", "Shizuku user service disconnected");
        } catch (JSONException e) {
            throw new ApiException("SHIZUKU_BAD_RESPONSE", "Shizuku returned invalid output");
        }
    }

    public static void disconnect() {
        synchronized (LOCK) {
            IShizukuShellService current = service;
            service = null;
            CompletableFuture<IShizukuShellService> pending = binding;
            binding = null;
            if (pending != null && !pending.isDone()) pending.cancel(false);
            if (current != null) {
                try { current.destroy(); } catch (RemoteException ignored) { }
            }
            if (appContext != null && safePing() && serviceArgs != null) {
                try { Shizuku.unbindUserService(serviceArgs, CONNECTION, true); }
                catch (RuntimeException ignored) { }
            }
            if (appContext != null) {
                try { Shizuku.removeBinderDeadListener(BINDER_DEAD); }
                catch (RuntimeException ignored) { }
            }
            serviceArgs = null;
            appContext = null;
        }
    }

    private static IShizukuShellService ensureBound() throws ApiException {
        IShizukuShellService current = service;
        if (current != null && current.asBinder().pingBinder()) return current;
        CompletableFuture<IShizukuShellService> future;
        synchronized (LOCK) {
            current = service;
            if (current != null && current.asBinder().pingBinder()) return current;
            if (binding == null || binding.isDone()) {
                binding = new CompletableFuture<>();
                try {
                    Shizuku.bindUserService(serviceArgs, CONNECTION);
                } catch (RuntimeException e) {
                    binding = null;
                    throw new ApiException("SHIZUKU_BIND_FAILED", "Unable to bind Shizuku user service");
                }
            }
            future = binding;
        }
        try {
            current = future.get(5, TimeUnit.SECONDS);
            if (current == null || !current.asBinder().pingBinder())
                throw new ApiException("SHIZUKU_BIND_FAILED", "Shizuku user service did not connect");
            return current;
        } catch (java.util.concurrent.TimeoutException e) {
            clearFailedBinding(future);
            throw new ApiException("SHIZUKU_BIND_TIMEOUT", "Shizuku user service bind timed out");
        } catch (InterruptedException e) {
            clearFailedBinding(future);
            Thread.currentThread().interrupt();
            throw new ApiException("TIMEOUT", "Shizuku bind interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new ApiException("SHIZUKU_BIND_FAILED", "Shizuku user service bind failed");
        }
    }

    private static void clearFailedBinding(CompletableFuture<IShizukuShellService> failed) {
        synchronized (LOCK) {
            if (binding == failed) binding = null;
        }
    }

    private static boolean safePing() {
        try { return Shizuku.pingBinder(); }
        catch (RuntimeException e) { return false; }
    }
}
