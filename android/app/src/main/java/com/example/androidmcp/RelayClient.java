package com.example.androidmcp;

import android.content.Context;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Optional outbound relay transport.
 *
 * <p>Design goals, in order: never weaken LAN/Tailscale, keep the relay
 * unable to read commands, survive NAT, reconnect cleanly, and never execute
 * a mutating action twice. TLS is mandatory (tls:// only); the relay
 * authenticates both sides with a pairing secret it knows, while RPC payloads
 * are end-to-end encrypted with the MCP token under
 * {@link LanSecureChannel#RELAY_DOMAIN} — the relay only forwards ciphertext.
 *
 * <p>Each received request carries a fresh 96-bit nonce; decrypt happens after
 * a replay pre-check, and responses are bound to the request nonce. A dropped
 * connection never triggers a resend of the in-flight request.
 */
public final class RelayClient {
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_FRAME_CHARS = 16 * 1024 * 1024;
    private static volatile boolean running;
    private static volatile String state = "disabled";
    private static volatile String lastError = "";
    private static volatile long attempts;
    private static volatile SSLSocket socket;
    private static Thread worker;

    private RelayClient() { }

    static String state() { return state; }
    static boolean isConnected() { return "connected".equals(state); }
    static String lastError() { return lastError; }
    static long attempts() { return attempts; }

    public static void start(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (!RelayConfig.isEnabled(app) || !RelayConfig.isConfigured(app)) {
                state = "disabled";
                lastError = "";
                return;
            }
            if (running) return;
            running = true;
            state = "connecting";
            lastError = "";
            worker = new Thread(() -> loop(app), "mcp-relay");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public static void stop(Context context) {
        Thread thread;
        synchronized (LOCK) {
            running = false;
            state = "disabled";
            thread = worker;
            worker = null;
            closeQuietly();
        }
        if (thread != null) thread.interrupt();
    }

    private static void loop(Context app) {
        long backoff = 1000;
        while (running) {
            try {
                connectAndServe(app);
                backoff = 1000;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                state = running ? "reconnecting" : "disabled";
            }
            if (!running) break;
            // Exponential backoff with jitter, capped at 60s.
            long sleep = Math.min(60_000, backoff) + RANDOM.nextInt(400);
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(60_000, backoff * 2);
        }
        state = "disabled";
    }

    private static void connectAndServe(Context app) throws Exception {
        String token = SecretStore.current(app);
        String secret = RelayConfig.secret(app);
        String room = RelayConfig.room(app);
        if (!RelayConfig.isValidSecret(secret) || !RelayConfig.isValidRoom(room)) {
            throw new IOException("relay not configured");
        }
        String endpoint = RelayConfig.url(app);
        if (!RelayConfig.isValidUrl(endpoint)) throw new IOException("invalid relay URL");
        String host = endpoint.substring("tls://".length(), endpoint.lastIndexOf(':'));
        int port = Integer.parseInt(endpoint.substring(endpoint.lastIndexOf(':') + 1));

        SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault()
                .createSocket();
        ssl.connect(new InetSocketAddress(host, port), 15_000);
        ssl.setSoTimeout(90_000);
        ssl.startHandshake();
        socket = ssl;
        attempts++;

        OutputStream out = ssl.getOutputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(
                ssl.getInputStream(), StandardCharsets.UTF_8));

        String clientNonce = randomHex(16);
        JSONObject hello = new JSONObject();
        hello.put("type", "hello");
        hello.put("role", "phone");
        hello.put("room", room);
        hello.put("nonce", clientNonce);
        hello.put("proof", authProof(secret, "phone", room, clientNonce));
        send(out, hello);

        JSONObject reply = readFrame(in);
        if (reply == null || !"hello_ok".equals(reply.optString("type"))) {
            throw new IOException("relay authentication rejected");
        }
        String serverNonce = reply.optString("nonce", "");
        String expected = okProof(secret, "phone", room, clientNonce, serverNonce);
        if (!constantTimeEquals(expected, reply.optString("proof", ""))) {
            throw new IOException("relay did not prove the pairing secret");
        }
        String session = sessionId(token, room, clientNonce, serverNonce);
        LanSecureChannel.ReplayGuard replayGuard = new LanSecureChannel.ReplayGuard();
        RpcDispatcher dispatcher = new RpcDispatcher(app);
        state = "connected";
        lastError = "";

        while (running && !ssl.isClosed()) {
            JSONObject frame;
            try {
                frame = readFrame(in);
            } catch (SocketTimeoutException timeout) {
                continue;
            }
            if (frame == null) throw new IOException("relay closed the connection");
            String type = frame.optString("type");
            if ("ping".equals(type)) {
                JSONObject pong = new JSONObject();
                pong.put("type", "pong");
                send(out, pong);
                continue;
            }
            if (!"data".equals(type)) continue;
            JSONObject envelope;
            try {
                envelope = new JSONObject(new String(Base64.decode(
                        frame.optString("payload", ""), Base64.NO_WRAP), StandardCharsets.UTF_8));
            } catch (Exception e) {
                continue; // opaque garbage from the relay: drop, never act
            }
            String requestNonce = envelope.optString("nonce", "");
            JSONObject request;
            try {
                request = LanSecureChannel.decryptRequest(token, session, envelope,
                        replayGuard, LanSecureChannel.RELAY_DOMAIN);
            } catch (RuntimeException e) {
                lastError = "relay payload rejected";
                continue;
            }
            JSONObject response;
            String method = request.optString("method", "");
            JSONObject params = request.optJSONObject("params");
            if (method.isEmpty()) {
                response = errorResponse("INVALID_ARGUMENT", requestNonce);
            } else {
                RequestScope scope = new RequestScope();
                RequestScope.CURRENT.set(scope);
                try {
                    Object result = dispatcher.dispatch(method, params == null ? new JSONObject() : params);
                    response = new JSONObject().put("result", result).put("requestNonce", requestNonce);
                } catch (ApiException e) {
                    response = errorResponse(e.code, requestNonce);
                } catch (Exception e) {
                    response = errorResponse("REMOTE_ERROR", requestNonce);
                } finally {
                    RequestScope.CURRENT.remove();
                }
            }
            JSONObject sealed = LanSecureChannel.encryptResponseDomain(
                    token, session, response, LanSecureChannel.RELAY_DOMAIN);
            JSONObject data = new JSONObject();
            data.put("type", "data");
            data.put("payload", Base64.encodeToString(
                    sealed.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            send(out, data);
        }
        throw new IOException("relay connection closed");
    }

    private static JSONObject errorResponse(String code, String requestNonce) {
        try {
            return new JSONObject()
                    .put("error", new JSONObject().put("code", code))
                    .put("requestNonce", requestNonce);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void send(OutputStream out, JSONObject frame) throws IOException {
        byte[] bytes = (frame.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        out.flush();
    }

    private static JSONObject readFrame(BufferedReader in) throws IOException {
        String line = in.readLine();
        if (line == null) return null;
        if (line.length() > MAX_FRAME_CHARS) throw new IOException("relay frame too large");
        try {
            return new JSONObject(line);
        } catch (JSONException e) {
            return null;
        }
    }

    private static void closeQuietly() {
        SSLSocket ssl = socket;
        socket = null;
        if (ssl != null) {
            try { ssl.close(); } catch (IOException ignored) { }
        }
    }

    static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        StringBuilder out = new StringBuilder();
        for (byte b : value) out.append(String.format(java.util.Locale.US, "%02x", b));
        return out.toString();
    }

    // ---- Crypto helpers (pure, unit-tested) --------------------------------

    static String authProof(String secretHex, String role, String room, String clientNonce) {
        return hex(hmac(secretHex, "mcp-android-relay-auth-v1\n" + role + "\n" + room + "\n" + clientNonce));
    }

    static String okProof(String secretHex, String role, String room, String clientNonce,
                          String serverNonce) {
        return hex(hmac(secretHex,
                "mcp-android-relay-auth-v1\nok\n" + role + "\n" + room + "\n"
                        + clientNonce + "\n" + serverNonce));
    }

    /** 32 hex chars: same shape the LAN channel expects for a session id. */
    static String sessionId(String token, String room, String clientNonce, String serverNonce) {
        String material = "mcp-android-relay-session-v1\n" + room + "\n"
                + clientNonce + "\n" + serverNonce;
        return hex(hmac(token, material)).substring(0, 32);
    }

    static byte[] hmac(String hexKey, String material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes(hexKey), "HmacSHA256"));
            return mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid relay secret");
        }
    }

    private static byte[] secretBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) throw new IllegalArgumentException("Invalid hex");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid hex");
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
