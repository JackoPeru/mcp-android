package com.example.androidmcp;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Point;
import android.os.PowerManager;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One-shot device unlock from the in-app stored PIN. Explicit RPC only, never
 * automatic: {@code unlock_device} wakes the screen and either swipes away a
 * non-secure keyguard or taps the stored digits into the system keyguard.
 *
 * <p>Single attempt per call; the outcome is always verified afterwards, so a
 * failure can never be mistaken for success. Numeric PIN only, entered through
 * the system keyguard package ({@code com.android.systemui}); OEM layouts
 * without tappable digit nodes fail closed with {@code PIN_ENTRY_FAILED}.
 */
public final class DeviceUnlock {
    private static final String KEYGUARD_PACKAGE = "com.android.systemui";
    private static final long WAKE_MS = 30_000;
    private static final long SETTLE_MS = 800;
    private static final long DIGIT_PAUSE_MS = 150;
    private static final long POLL_MS = 250;
    private static final long VERIFY_MS = 3_000;

    private DeviceUnlock() { }

    public static JSONObject unlock(Context context, McpAccessibilityService service) throws ApiException {
        Context app = context.getApplicationContext();
        KeyguardManager keyguard = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null) throw new ApiException("UNLOCK_FAILED", "Keyguard status unavailable");
        if (!keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked()) return done(true, "none");
        if (!DevicePinStore.hasPin(app)) {
            throw new ApiException("PIN_NOT_SET", "Store a device PIN in-app first");
        }
        if (DevicePinStore.isLockedOut(app)) {
            throw new ApiException("PIN_LOCKED", "Too many wrong attempts: re-save the PIN in-app");
        }
        boolean secure = keyguard.isKeyguardSecure();
        android.util.Log.d(TAG, "unlock start secure=" + secure);
        PowerManager.WakeLock wake = wake(app);
        try {
            awaitInteractive(app, 5_000);
            if (!secure) {
                swipeUp(service);
            } else {
                // Clock/shade states need a swipe (or two) before the PIN pad
                // appears. Retry only while zero digits were tapped: completing
                // a partial entry with a second pass would forge a wrong PIN.
                String pin = DevicePinStore.loadPin(app);
                int[] tapped = {0};
                ApiException lastError = null;
                for (int round = 0; round < 3; round++) {
                    tapped[0] = 0;
                    try { swipeUp(service); } catch (ApiException ignored) { }
                    sleep(SETTLE_MS);
                    try {
                        enterPin(service, pin, tapped);
                        lastError = null;
                        break;
                    } catch (ApiException e) {
                        if (!"PIN_ENTRY_FAILED".equals(e.code)) throw e;
                        lastError = e;
                        if (tapped[0] > 0) break;
                    }
                }
                if (lastError != null) throw lastError;
            }
            android.util.Log.d(TAG, "entry done, verifying");
            boolean unlocked = awaitUnlocked(keyguard, VERIFY_MS);
            if (unlocked) {
                DevicePinStore.clearFailures(app);
                return done(true, secure ? "pin" : "swipe");
            }
            DevicePinStore.noteFailure(app);
            throw new ApiException("WRONG_PIN", "Device still locked after one attempt");
        } finally {
            if (wake != null && wake.isHeld()) {
                try { wake.release(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static PowerManager.WakeLock wake(Context app) throws ApiException {
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (power == null) throw new ApiException("UNLOCK_FAILED", "Power service unavailable");
        try {
            PowerManager.WakeLock lock = power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "mcp:unlock");
            lock.acquire(WAKE_MS);
            return lock;
        } catch (RuntimeException e) {
            throw new ApiException("UNLOCK_FAILED", "Unable to wake the screen");
        }
    }

    private static void awaitInteractive(Context app, long timeoutMs) {
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (power != null && power.isInteractive()) return;
            sleep(POLL_MS);
        }
    }

    private static final String TAG = "DeviceUnlock";

    private static void swipeUp(McpAccessibilityService service) throws ApiException {
        Point size = service.screenSize();
        long x = size.x / 2L;
        // Long edge-to-edge swipe, twice: some keyguards (AOD → lockscreen)
        // need the first one just to fully wake the layer. Uses the keyguard
        // gesture path: the normal one refuses locked screens by design.
        ApiException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                boolean performed = service.swipeOnKeyguard(x, size.y - 50L, x, size.y / 4L, 600L);
                android.util.Log.d(TAG, "swipeUp attempt " + attempt + " performed=" + performed);
                if (performed) return;
                last = new ApiException("UNLOCK_FAILED", "Swipe up rejected");
            } catch (ApiException e) {
                last = e;
                android.util.Log.d(TAG, "swipeUp attempt " + attempt + " error=" + e.code);
            } catch (RuntimeException e) {
                last = new ApiException("UNLOCK_FAILED", "Swipe up failed");
                android.util.Log.d(TAG, "swipeUp attempt " + attempt + " runtime=" + e);
            }
            sleep(500);
        }
        throw last == null ? new ApiException("UNLOCK_FAILED", "Swipe up failed") : last;
    }

    private static void enterPin(McpAccessibilityService service, String pin, int[] tapped) throws ApiException {
        for (int i = 0; i < pin.length(); i++) {
            tapDigit(service, String.valueOf(pin.charAt(i)));
            tapped[0]++;
            sleep(DIGIT_PAUSE_MS);
        }
    }

    private static void tapDigit(McpAccessibilityService service, String digit) throws ApiException {
        // Exact text match first inside the system keyguard, then anywhere:
        // on the keyguard screen an exact single-digit text is unambiguous.
        ApiException keyguardMiss = null;
        for (boolean scoped : new boolean[]{true, false}) {
            JSONObject selector = new JSONObject();
            try {
                selector.put("text", digit);
                if (scoped) selector.put("packageName", KEYGUARD_PACKAGE);
            } catch (JSONException e) {
                throw new ApiException("INTERNAL", "Unable to encode digit selector");
            }
            try {
                service.clickSelector(selector, 0);
                return;
            } catch (ApiException e) {
                if (scoped) { keyguardMiss = e; continue; }
                throw new ApiException("PIN_ENTRY_FAILED",
                        "Keyguard digit not tappable on this device (" + e.code + ")");
            }
        }
        throw new ApiException("PIN_ENTRY_FAILED",
                "Keyguard digit not tappable on this device ("
                        + (keyguardMiss == null ? "unknown" : keyguardMiss.code) + ")");
    }

    private static boolean awaitUnlocked(KeyguardManager keyguard, long timeoutMs) {
        long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                if (!keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked()) return true;
            } catch (RuntimeException ignored) { }
            sleep(POLL_MS);
        }
        return false;
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static JSONObject done(boolean unlocked, String method) throws ApiException {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("unlocked", unlocked);
            result.put("method", method);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode unlock result");
        }
    }
}
