package com.example.androidmcp;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Stores only the current bearer secret in private app preferences.
 *
 * <p>Blind-spot note (not unit-testable): the token rests in cleartext in
 * MODE_PRIVATE prefs. That is the Android default without EncryptedSharedPreferences;
 * on a rooted device, via {@code adb backup} (blocked by allowBackup=false),
 * {@code run-as}, or Shizuku file read, it is extractable. Mitigations in place:
 * allowBackup=false + dataExtractionRules exclude sharedprefs from cloud transfer,
 * FLAG_SECURE token dialog, stop-on-show, and manual rotation revoking old sessions.
 * A future step is EncryptedSharedPreferences/Keystore; it needs a new dependency
 * and migration test on real devices, so it is intentionally not done blind here.
 *
 * <p>Rotation (dependency-free): {@link #rotate} mints a fresh 256-bit hex token
 * and revokes the old one immediately. The UI rotates on every token reveal and
 * on demand; treat any export of this file as a compromise and rotate. Backup
 * stays excluded via {@code allowBackup=false} + dataExtractionRules, so the
 * token never leaves the device through backup or cloud transfer.
 */
public final class SecretStore {
    private static final String PREFS = "android_private_mcp";
    private static final String TOKEN = "rpc_token";
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretStore() { }

    public static synchronized String current(Context context) {
        SharedPreferences prefs = prefs(context);
        String token = prefs.getString(TOKEN, null);
        if (SecurityValidators.isValidToken(token)) {
            return token;
        }
        return rotate(context);
    }

    public static synchronized String rotate(Context context) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        char[] hex = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            hex[i * 2] = digits[value >>> 4];
            hex[i * 2 + 1] = digits[value & 0x0f];
        }
        String token = new String(hex);
        if (!prefs(context).edit().putString(TOKEN, token).commit()) {
            throw new IllegalStateException("Unable to persist RPC secret");
        }
        return token;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
