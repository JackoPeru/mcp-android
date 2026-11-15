package com.example.androidmcp;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Relay settings (opt-in). The relay never sees the MCP token: authentication
 * uses a separate pairing secret, while RPC payloads are end-to-end encrypted
 * with the MCP token under a relay-specific domain.
 */
public final class RelayConfig {
    private static final String KEY_ENABLED = "relay_enabled";
    private static final String KEY_URL = "relay_url";
    private static final String KEY_ROOM = "relay_room";
    private static final String KEY_SECRET = "relay_secret";
    private static final java.util.regex.Pattern URL =
            java.util.regex.Pattern.compile("^tls://[A-Za-z0-9.-]{1,253}:[0-9]{1,5}$");
    private static final java.util.regex.Pattern ROOM =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final java.util.regex.Pattern SECRET =
            java.util.regex.Pattern.compile("^[a-f0-9]{64}$");

    private RelayConfig() { }

    public static boolean isEnabled(Context context) {
        return PrefsFlags.prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static String url(Context context) {
        return PrefsFlags.prefs(context).getString(KEY_URL, "");
    }

    public static String room(Context context) {
        return PrefsFlags.prefs(context).getString(KEY_ROOM, "");
    }

    public static String secret(Context context) {
        return PrefsFlags.prefs(context).getString(KEY_SECRET, "");
    }

    public static boolean isConfigured(Context context) {
        return URL.matcher(url(context)).matches()
                && ROOM.matcher(room(context)).matches()
                && SECRET.matcher(secret(context)).matches();
    }

    /** Masked view for status/UI: never returns the pairing secret. */
    public static JSONObject describe(Context context) throws ApiException {
        try {
            String secret = secret(context);
            return new JSONObject()
                    .put("enabled", isEnabled(context))
                    .put("configured", isConfigured(context))
                    .put("url", url(context))
                    .put("room", room(context))
                    .put("secretMasked", secret.isEmpty() ? "" : secret.substring(0, 4) + "…" + secret.substring(60))
                    .put("transport", "tls")
                    .put("state", RelayClient.state())
                    .put("connected", RelayClient.isConnected())
                    .put("lastError", RelayClient.lastError())
                    .put("attempts", RelayClient.attempts());
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode relay settings");
        }
    }

    @SuppressWarnings("ApplySharedPref")
    public static void save(Context context, boolean enabled, String url, String room, String secret) {
        PrefsFlags.prefs(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_URL, url == null ? "" : url.trim())
                .putString(KEY_ROOM, room == null ? "" : room.trim())
                .putString(KEY_SECRET, secret == null ? "" : secret.trim().toLowerCase(java.util.Locale.ROOT))
                .commit();
    }

    static boolean isValidUrl(String value) { return value != null && URL.matcher(value).matches(); }
    static boolean isValidRoom(String value) { return value != null && ROOM.matcher(value).matches(); }
    static boolean isValidSecret(String value) { return value != null && SECRET.matcher(value).matches(); }
}
