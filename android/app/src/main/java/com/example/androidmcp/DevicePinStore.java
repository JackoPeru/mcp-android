package com.example.androidmcp;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Device PIN stored ONLY here, encrypted with an Android Keystore key.
 *
 * <p>There is deliberately no read-back API: the PIN is entered in-app, never
 * leaves the device, and no tool returns it. {@link #isValidPin} is pure and
 * unit-tested; everything else needs the Android Keystore.
 *
 * <p>Accepted trade-off, shown in-app before enabling: anyone holding the MCP
 * token can ask for one unlock attempt. Mitigations: explicit RPC only (never
 * automatic), single attempt per call, lockout after {@link #MAX_FAILURES}
 * consecutive failures until the PIN is re-saved.
 */
public final class DevicePinStore {
    private static final String TAG = "DevicePinStore";
    static final int MAX_FAILURES = 5;
    private static final String PREFS = "android_private_mcp";
    private static final String KEY_PIN = "device_pin_enc";
    private static final String KEY_FAILURES = "device_pin_failures";
    private static final String KEY_ALIAS = "mcp_device_pin";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private DevicePinStore() { }

    /** 4-16 decimal digits. PIN only: patterns and passwords are not supported. */
    public static boolean isValidPin(String pin) {
        if (pin == null || pin.length() < 4 || pin.length() > 16) return false;
        for (int i = 0; i < pin.length(); i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public static boolean hasPin(Context context) {
        return prefs(context).contains(KEY_PIN);
    }

    public static void savePin(Context context, String pin) throws ApiException {
        if (!isValidPin(pin)) throw new ApiException("INVALID_ARGUMENT", "PIN must be 4-16 digits");
        try {
            savePinOnce(context, pin);
        } catch (ApiException e) {
            throw e;
        } catch (Exception first) {
            android.util.Log.e(TAG, "savePin failed, resetting alias and retrying once", first);
            resetAlias();
            try {
                savePinOnce(context, pin);
            } catch (ApiException e) {
                throw e;
            } catch (Exception retry) {
                android.util.Log.e(TAG, "savePin retry failed", retry);
                throw new ApiException("PIN_KEYSTORE_FAILED", "Device keystore unavailable");
            }
        }
    }

    private static void savePinOnce(Context context, String pin) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        // No explicit IV: with randomized encryption required, AndroidKeyStore
        // rejects caller-provided IVs ("called-provider iv not permitted").
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != 12) throw new IOException("Keystore did not return a GCM IV");
        byte[] ciphertext = cipher.doFinal(pin.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        if (!prefs(context).edit()
                .putString(KEY_PIN, Base64.encodeToString(combined, Base64.NO_WRAP))
                .putInt(KEY_FAILURES, 0)
                .commit()) {
            throw new ApiException("PIN_STORE_FAILED", "Unable to persist PIN");
        }
    }

    private static void resetAlias() {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        } catch (Exception e) {
            android.util.Log.e(TAG, "alias reset failed", e);
        }
    }

    public static void clearPin(Context context) {
        prefs(context).edit().remove(KEY_PIN).remove(KEY_FAILURES).apply();
    }

    static String loadPin(Context context) throws ApiException {
        String stored = prefs(context).getString(KEY_PIN, null);
        if (stored == null) throw new ApiException("PIN_NOT_SET", "No device PIN stored in-app");
        try {
            byte[] combined = Base64.decode(stored, Base64.NO_WRAP);
            if (combined.length < 13) throw new ApiException("PIN_KEYSTORE_FAILED", "Stored PIN is corrupt");
            byte[] iv = java.util.Arrays.copyOfRange(combined, 0, 12);
            byte[] ciphertext = java.util.Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            String pin = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            if (!isValidPin(pin)) throw new ApiException("PIN_KEYSTORE_FAILED", "Stored PIN is corrupt");
            return pin;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            android.util.Log.e(TAG, "loadPin failed", e);
            throw new ApiException("PIN_KEYSTORE_FAILED", "Device keystore unavailable");
        }
    }

    static int failures(Context context) {
        return prefs(context).getInt(KEY_FAILURES, 0);
    }

    static boolean isLockedOut(Context context) {
        return failures(context) >= MAX_FAILURES;
    }

    static void noteFailure(Context context) {
        SharedPreferences prefs = prefs(context);
        prefs.edit().putInt(KEY_FAILURES, prefs.getInt(KEY_FAILURES, 0) + 1).apply();
    }

    static void clearFailures(Context context) {
        prefs(context).edit().putInt(KEY_FAILURES, 0).apply();
    }

    /**
     * Round-trip self-test shown in the unlock card, so a broken Keystore is
     * visible before the user taps save. Returns null when healthy, otherwise
     * a short diagnostic string. Has the side effect of generating the key.
     */
    static String probe() {
        try {
            SecretKey k = key();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, k);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != 12) return "keystore returned no GCM IV";
            byte[] ciphertext = cipher.doFinal(new byte[]{1});
            Cipher plain = Cipher.getInstance(TRANSFORMATION);
            plain.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (plain.doFinal(ciphertext)[0] != 1) return "round-trip mismatch";
            return null;
        } catch (Exception e) {
            android.util.Log.e(TAG, "keystore probe failed", e);
            String detail = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            return detail.length() > 120 ? detail.substring(0, 120) : detail;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
