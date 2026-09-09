package com.example.androidmcp;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated application-layer encryption for LAN RPC traffic. */
public final class LanSecureChannel {
    public static final String MEDIA_TYPE = "application/mcp-android-lan+json";
    public static final String PROTOCOL = "mcp-android-lan-channel";
    public static final int VERSION = 1;
    private static final String DOMAIN = "mcp-android-lan-channel-v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private LanSecureChannel() { }

    public static String newSessionId() {
        byte[] value = new byte[16];
        RANDOM.nextBytes(value);
        return hex(value);
    }

    public static JSONObject helloResponse(String token, String nonce, String session) {
        validateToken(token);
        validateHelloNonce(nonce);
        validateSession(session);
        try {
            return new JSONObject()
                    .put("protocol", PROTOCOL)
                    .put("version", VERSION)
                    .put("nonce", nonce)
                    .put("session", session)
                    .put("proof", helloProof(token, nonce, session));
        } catch (JSONException e) {
            throw new IllegalArgumentException("Unable to encode LAN hello", e);
        }
    }

    static String helloProof(String token, String nonce, String session) {
        validateToken(token);
        validateHelloNonce(nonce);
        validateSession(session);
        return hmacHex(token, DOMAIN + "\nhello\n" + nonce + "\n" + session);
    }

    public static JSONObject decryptRequest(String token, String session, JSONObject envelope, ReplayGuard replayGuard) {
        JSONObject result = decrypt(token, session, "request", envelope);
        if (replayGuard != null && !replayGuard.accept(envelope.optString("nonce", ""))) {
            throw new IllegalArgumentException("LAN request replay rejected");
        }
        return result;
    }

    public static JSONObject encryptResponse(String token, String session, JSONObject payload) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        return encrypt(token, session, "response", payload, hex(nonce));
    }

    static JSONObject encryptResponse(String token, String session, JSONObject payload, String nonceHex) {
        return encrypt(token, session, "response", payload, nonceHex);
    }

    private static JSONObject encrypt(String token, String session, String direction,
                                      JSONObject payload, String nonceHex) {
        validateToken(token);
        validateSession(session);
        validateNonce(nonceHex);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(deriveKey(token, session, direction), "AES"),
                    new GCMParameterSpec(128, hexBytes(nonceHex)));
            cipher.updateAAD(aad(direction, session));
            byte[] ciphertext = cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
            return new JSONObject()
                    .put("version", VERSION)
                    .put("session", session)
                    .put("nonce", nonceHex)
                    .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        } catch (GeneralSecurityException | JSONException e) {
            throw new IllegalArgumentException("Unable to encrypt LAN payload", e);
        }
    }

    private static JSONObject decrypt(String token, String session, String direction, JSONObject envelope) {
        validateToken(token);
        validateSession(session);
        if (envelope == null || envelope.length() != 4
                || !envelope.has("version") || !envelope.has("session")
                || !envelope.has("nonce") || !envelope.has("ciphertext")) {
            throw new IllegalArgumentException("Invalid LAN channel envelope");
        }
        try {
            if (envelope.getInt("version") != VERSION || !session.equals(envelope.getString("session"))) {
                throw new IllegalArgumentException("Invalid LAN channel envelope");
            }
            String nonceHex = envelope.getString("nonce");
            String encoded = envelope.getString("ciphertext");
            validateNonce(nonceHex);
            if (encoded.isEmpty() || encoded.length() > 12 * 1024 * 1024) {
                throw new IllegalArgumentException("Invalid LAN channel envelope");
            }
            byte[] ciphertext = Base64.getDecoder().decode(encoded);
            if (ciphertext.length < 17) throw new IllegalArgumentException("Invalid LAN channel envelope");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(deriveKey(token, session, direction), "AES"),
                    new GCMParameterSpec(128, hexBytes(nonceHex)));
            cipher.updateAAD(aad(direction, session));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return parseObject(plaintext);
        } catch (JSONException | GeneralSecurityException | IllegalArgumentException e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            throw new IllegalArgumentException("Invalid LAN channel envelope", e);
        }
    }

    private static JSONObject parseObject(byte[] bytes) {
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            if (!SecurityValidators.isBoundedJson(json)) throw new IllegalArgumentException("Invalid LAN plaintext");
            JSONTokener tokener = new JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
                throw new IllegalArgumentException("Invalid LAN plaintext");
            }
            return (JSONObject) value;
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid LAN plaintext", e);
        }
    }

    private static byte[] deriveKey(String token, String session, String direction) {
        if (!"request".equals(direction) && !"response".equals(direction)) {
            throw new IllegalArgumentException("Invalid LAN channel direction");
        }
        return hmac(token, DOMAIN + "\n" + session + "\n" + direction);
    }

    private static byte[] aad(String direction, String session) {
        if (!"request".equals(direction) && !"response".equals(direction)) {
            throw new IllegalArgumentException("Invalid LAN channel direction");
        }
        return (DOMAIN + "\n" + direction + "\n" + session).getBytes(StandardCharsets.UTF_8);
    }

    private static String hmacHex(String token, String material) {
        return hex(hmac(token, material));
    }

    private static byte[] hmac(String token, String material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hexBytes(token), "HmacSHA256"));
            return mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to create LAN channel HMAC", e);
        }
    }

    private static void validateToken(String token) {
        if (!SecurityValidators.isValidToken(token)) throw new IllegalArgumentException("Invalid LAN token");
    }

    private static void validateSession(String session) {
        if (session == null || session.length() != 32 || !isLowerHex(session)) {
            throw new IllegalArgumentException("Invalid LAN session");
        }
    }

    private static void validateNonce(String nonce) {
        if (nonce == null || nonce.length() != 24 || !isLowerHex(nonce)) {
            throw new IllegalArgumentException("Invalid LAN nonce");
        }
    }

    private static void validateHelloNonce(String nonce) {
        if (nonce == null || nonce.isEmpty() || nonce.length() > 64) {
            throw new IllegalArgumentException("Invalid hello nonce");
        }
        for (int i = 0; i < nonce.length(); i++) {
            char c = nonce.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '-' || c == '_';
            if (!ok) throw new IllegalArgumentException("Invalid hello nonce");
        }
    }

    private static boolean isLowerHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }

    private static byte[] hexBytes(String value) {
        if ((value.length() & 1) != 0 || !isLowerHex(value)) throw new IllegalArgumentException("Invalid hex");
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            out[i * 2] = digits[value >>> 4];
            out[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(out);
    }

    public static final class ReplayGuard {
        // Only successfully authenticated requests enter this set, so a LAN attacker
        // without the token cannot grow it. Keep a long replay horizon for sessions
        // that remain active for days without turning this into unbounded memory.
        private static final int MAX_NONCES = 65_536;
        private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();

        public synchronized boolean accept(String nonce) {
            validateNonce(nonce);
            if (seen.containsKey(nonce)) return false;
            if (seen.size() >= MAX_NONCES) {
                Iterator<Map.Entry<String, Boolean>> iterator = seen.entrySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            seen.put(nonce, Boolean.TRUE);
            return true;
        }
    }
}
