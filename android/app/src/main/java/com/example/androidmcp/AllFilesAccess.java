package com.example.androidmcp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;

/**
 * Flag-gated access to all shared storage via MANAGE_EXTERNAL_STORAGE.
 *
 * <p>Android's picker (SAF) cannot grant the whole volume, Download, or
 * {@code Android/data}: this backend covers everything else with one switch.
 * It stays fully inert unless the user BOTH flips the in-app flag AND grants
 * "All files access" in system Settings. Either side off means the file RPCs
 * behave exactly as before (SAF roots only, unknown root otherwise).
 *
 * <p>Even when active, {@code Android/data} and {@code Android/obb} stay
 * blocked by {@link AllFilesStore}: the OS denies them too, but a clear
 * {@code OPERATION_UNSUPPORTED} beats a cryptic I/O failure.
 */
public final class AllFilesAccess {
    private static final String KEY_ENABLED = "all_files_enabled";

    private AllFilesAccess() { }

    public static synchronized boolean isEnabled(Context context) {
        return PrefsFlags.prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static synchronized void setEnabled(Context context, boolean enabled) {
        // apply(), not commit(): if persistence fails the flag silently stays
        // off, which is the fail-closed direction.
        PrefsFlags.prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** OS-level grant, toggled by the user in system Settings only. */
    public static boolean isGranted() {
        return Environment.isExternalStorageManager();
    }

    /** Effective gate checked by every file RPC. */
    public static boolean isActive(Context context) {
        return isEnabled(context) && isGranted();
    }

    /** Intent opening this app's "All files access" settings page. */
    public static Intent manageIntent(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (RuntimeException e) {
            Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return fallback;
        }
    }
}
