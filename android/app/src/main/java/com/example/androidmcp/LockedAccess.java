package com.example.androidmcp;

import android.annotation.SuppressLint;
import android.content.Context;

/**
 * What an agent may do while the device is locked.
 *
 * <p>Flag ON (default, current behavior): everything except on-screen UI
 * works while locked — files, shell, notifications, intents. Flag OFF
 * (locked-down): while locked only notification listing plus the two
 * session-lifecycle calls ({@code unlock_device} to exit the state,
 * {@code ui_done} as a harmless no-op) are allowed; anything else answers
 * {@code LOCKED_UI}. UI reads and gestures always require an unlocked
 * screen regardless of this flag.
 */
public final class LockedAccess {
    private static final String KEY_FULL = "full_locked_access";

    private LockedAccess() { }

    /** Default true: preserves the historical behavior. */
    public static synchronized boolean isFullAccess(Context context) {
        return PrefsFlags.prefs(context).getBoolean(KEY_FULL, true);
    }

    /**
     * @return false when persistence failed; the caller must surface it,
     * because silently keeping the old posture (especially permissive) would
     * lie to the user. commit() is intentional here (see suppression).
     */
    @SuppressLint("ApplySharedPref")
    public static synchronized boolean setFullAccess(Context context, boolean full) {
        return PrefsFlags.prefs(context).edit().putBoolean(KEY_FULL, full).commit();
    }

    /**
     * Pure decision rule, unit-tested. With full access nothing is blocked
     * here (each UI method still enforces its own {@code requireUnlocked});
     * locked-down mode blocks everything while locked except notification
     * listing plus the two session-lifecycle calls.
     */
    static boolean blockedWhileLocked(String method, boolean fullAccess) {
        if (fullAccess) return false;
        return !(method != null
                && (method.equals("notifications")
                    || method.equals("unlock_device")
                    || method.equals("ui_done")));
    }
}
