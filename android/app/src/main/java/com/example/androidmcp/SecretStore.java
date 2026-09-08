package com.example.androidmcp;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Stores only the current bearer secret in private app preferences. */
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
