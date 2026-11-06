package com.example.androidmcp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.security.SecureRandom;

/**
 * Stores only the current bearer secret, encrypted with an Android Keystore
 * key via EncryptedSharedPreferences (AES-256-GCM values, AES-256-SIV keys).
 * No plaintext fallback: if the Keystore is unavailable the session fails
 * closed instead of persisting a cleartext token.
 *
 * <p>Migration: installs that already hold a cleartext token in the legacy
 * {@code MODE_PRIVATE} prefs move it into the encrypted store on first read
 * and wipe the legacy entry. Treat any export of the legacy file as a
 * compromise and rotate.
 *
 * <p>Rotation (dependency-free): {@link #rotate} mints a fresh 256-bit hex
 * token and revokes the old one immediately. The UI rotates the token on
 * every reveal and on demand. Backup stays excluded via
 * {@code allowBackup=false} + dataExtractionRules, so the token never leaves
 * the device through backup or cloud transfer.
 */
public final class SecretStore {
    private static final String PREFS = "android_private_mcp";
    private static final String ENC_PREFS = "android_private_mcp_enc";
    private static final String TOKEN = "rpc_token";
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretStore() { }

    public static synchronized String current(Context context) {
        Context app = context.getApplicationContext();
        String encrypted = readEncrypted(app);
        String legacy = legacyPrefs(app).getString(TOKEN, null);
        String picked = pickCurrent(encrypted, legacy);
        if (picked != null) {
            if (picked == legacy) {
                // One-time migration: persist encrypted first, wipe legacy after.
                writeEncrypted(app, legacy);
                legacyPrefs(app).edit().remove(TOKEN).apply();
            } else if (legacy != null) {
                // Stale cleartext left by an interrupted migration: wipe it.
                legacyPrefs(app).edit().remove(TOKEN).apply();
            }
            return picked;
        }
        return rotate(context);
    }

    /**
     * Pure precedence rule, unit-tested: encrypted wins, then a valid legacy
     * token (the caller migrates it), otherwise null so the caller rotates.
     * Identity comparison on the legacy branch tells migration apart.
     */
    static String pickCurrent(String encrypted, String legacy) {
        if (SecurityValidators.isValidToken(encrypted)) return encrypted;
        if (SecurityValidators.isValidToken(legacy)) return legacy;
        return null;
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
        writeEncrypted(context.getApplicationContext(), token);
        return token;
    }

    private static SharedPreferences encryptedPrefs(Context app) {
        try {
            MasterKey masterKey = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    app,
                    ENC_PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            throw new IllegalStateException("Encrypted token store unavailable", e);
        }
    }

    private static String readEncrypted(Context app) {
        try {
            return encryptedPrefs(app).getString(TOKEN, null);
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Encrypted token store unavailable", e);
        }
    }

    private static void writeEncrypted(Context app, String token) {
        // commit(), not apply(): a lost token would desync every agent.
        if (!encryptedPrefs(app).edit().putString(TOKEN, token).commit()) {
            throw new IllegalStateException("Unable to persist RPC secret");
        }
    }

    private static SharedPreferences legacyPrefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
