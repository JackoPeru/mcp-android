package com.example.androidmcp;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Strict LAN discovery wire format with proof-of-secret, but never the secret itself. */
public final class LanDiscoveryProtocol {
    static final int MAX_PACKET_BYTES = 512;
    static final String PROTOCOL = "mcp-android-discovery";
    static final int VERSION = 1;

    private LanDiscoveryProtocol() { }

    public static final class Request {
        public final String nonce;
        private Request(String nonce) { this.nonce = nonce; }
    }

    public static Request parseRequest(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Invalid discovery datagram");
        }
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            JSONTokener tokener = new JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
                throw new IllegalArgumentException("Invalid discovery JSON");
            }
            JSONObject object = (JSONObject) value;
            if (object.length() != 3 || !object.has("protocol") || !object.has("version") || !object.has("nonce")) {
                throw new IllegalArgumentException("Invalid discovery fields");
            }
            if (!PROTOCOL.equals(object.getString("protocol")) || object.getInt("version") != VERSION) {
                throw new IllegalArgumentException("Unsupported discovery protocol");
            }
            String nonce = object.getString("nonce");
            if (nonce.length() < 1 || nonce.length() > 64) throw new IllegalArgumentException("Invalid nonce");
            for (int i = 0; i < nonce.length(); i++) {
                char c = nonce.charAt(i);
                boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                        || c >= '0' && c <= '9' || c == '-' || c == '_';
                if (!ok) throw new IllegalArgumentException("Invalid nonce");
            }
            return new Request(nonce);
        } catch (JSONException | RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            throw new IllegalArgumentException("Invalid discovery datagram", e);
        }
    }

    public static byte[] encodeResponse(Request request, TransportEndpoint lan, String token) {
        if (request == null || lan == null || !"lan".equals(lan.transport)) {
            throw new IllegalArgumentException("LAN response required");
        }
        try {
            JSONObject object = new JSONObject();
            object.put("protocol", PROTOCOL);
            object.put("version", VERSION);
            object.put("nonce", request.nonce);
            object.put("address", lan.address);
            object.put("port", lan.port);
            object.put("transport", "lan");
            object.put("proof", discoveryProof(token, request, lan));
            byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_PACKET_BYTES) throw new IllegalArgumentException("Discovery response too large");
            return bytes;
        } catch (JSONException e) {
            throw new IllegalArgumentException("Unable to encode discovery response", e);
        }
    }

    static String discoveryProof(String token, Request request, TransportEndpoint lan) {
        if (!SecurityValidators.isValidToken(token)
                || request == null || lan == null || !"lan".equals(lan.transport)) {
            throw new IllegalArgumentException("Valid discovery proof inputs required");
        }
        String material = PROTOCOL + "\n" + VERSION + "\n" + request.nonce + "\n"
                + lan.address + "\n" + lan.port + "\nlan";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hexBytes(token), "HmacSHA256"));
            return hex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to create discovery proof", e);
        }
    }

    private static byte[] hexBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid token");
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
}
