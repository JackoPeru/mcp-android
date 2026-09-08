package com.example.androidmcp;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;

/** Strict, credential-free LAN discovery wire format. */
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

    public static byte[] encodeResponse(Request request, TransportEndpoint lan) {
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
            byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_PACKET_BYTES) throw new IllegalArgumentException("Discovery response too large");
            return bytes;
        } catch (JSONException e) {
            throw new IllegalArgumentException("Unable to encode discovery response", e);
        }
    }
}
