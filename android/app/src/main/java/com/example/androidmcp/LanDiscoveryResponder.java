package com.example.androidmcp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Passive LAN-only UDP discovery responder. The bearer is used only as an HMAC key and never sent. */
public final class LanDiscoveryResponder {
    public static final int PORT = 8_766;
    private final AtomicBoolean running = new AtomicBoolean();
    private final SourceRateLimiter limiter = new SourceRateLimiter();
    private volatile DatagramSocket socket;
    private volatile TransportEndpoint endpoint;
    private volatile String token;

    public synchronized void start(TransportEndpoint lan, String bearerToken) throws IOException {
        if (lan == null || !"lan".equals(lan.transport) || lan.prefixLength < 1 || lan.prefixLength > 30) {
            throw new IllegalArgumentException("Valid LAN endpoint required");
        }
        if (!SecurityValidators.isValidToken(bearerToken)) {
            throw new IllegalArgumentException("Valid discovery bearer required");
        }
        if (running.get() && lan.equals(endpoint) && bearerToken.equals(token)) return;
        stop();
        DatagramSocket candidate = new DatagramSocket(null);
        try {
            candidate.setReuseAddress(true);
            candidate.setBroadcast(true);
            // Android uses the Linux UDP stack: a socket bound only to the host's
            // unicast address does not reliably receive subnet-broadcast datagrams.
            // Bind the concrete subnet broadcast address, never a wildcard address.
            candidate.bind(new InetSocketAddress(InetAddress.getByName(bindAddress(lan)), PORT));
            endpoint = lan;
            token = bearerToken;
            socket = candidate;
            running.set(true);
            Thread thread = new Thread(this::loop, "android-mcp-lan-discovery");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException | RuntimeException e) {
            candidate.close();
            endpoint = null;
            token = null;
            socket = null;
            running.set(false);
            throw e;
        }
    }

    public synchronized void stop() {
        running.set(false);
        DatagramSocket current = socket;
        socket = null;
        endpoint = null;
        token = null;
        if (current != null) current.close();
    }

    public boolean isRunning() { return running.get(); }

    static String bindAddress(TransportEndpoint lan) {
        if (lan == null || !"lan".equals(lan.transport)) {
            throw new IllegalArgumentException("Valid LAN endpoint required");
        }
        return NetworkAddressPolicy.broadcastAddress(lan.address, lan.prefixLength);
    }

    private void loop() {
        byte[] buffer = new byte[LanDiscoveryProtocol.MAX_PACKET_BYTES + 1];
        while (running.get()) {
            DatagramSocket current = socket;
            TransportEndpoint lan = endpoint;
            String bearerToken = token;
            if (current == null || lan == null || bearerToken == null) return;
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                current.receive(packet);
                if (packet.getLength() > LanDiscoveryProtocol.MAX_PACKET_BYTES) continue;
                InetAddress sourceAddress = packet.getAddress();
                String source = sourceAddress == null ? "" : sourceAddress.getHostAddress();
                try {
                    if (!NetworkAddressPolicy.isRfc1918(source)
                            || !NetworkAddressPolicy.subnetContains(lan.address, lan.prefixLength, source)) continue;
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (!limiter.allow(source, System.currentTimeMillis())) continue;
                byte[] requestBytes = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), requestBytes, 0, packet.getLength());
                LanDiscoveryProtocol.Request request;
                try { request = LanDiscoveryProtocol.parseRequest(requestBytes); }
                catch (IllegalArgumentException e) { continue; }
                byte[] response = LanDiscoveryProtocol.encodeResponse(request, lan, bearerToken);
                DatagramPacket reply = new DatagramPacket(response, response.length, sourceAddress, packet.getPort());
                current.send(reply);
            } catch (SocketException e) {
                if (running.get()) continue;
                return;
            } catch (IOException ignored) {
                if (!running.get()) return;
            }
        }
    }

    static final class SourceRateLimiter {
        private static final int MAX_SOURCES = 64;
        private final LinkedHashMap<String, Long> lastResponse = new LinkedHashMap<>();

        synchronized boolean allow(String source, long nowMs) {
            Long previous = lastResponse.get(source);
            if (previous != null && nowMs - previous < 1_000L) return false;
            if (previous == null && lastResponse.size() >= MAX_SOURCES) {
                Iterator<Map.Entry<String, Long>> iterator = lastResponse.entrySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            lastResponse.remove(source);
            lastResponse.put(source, nowMs);
            return true;
        }

        synchronized int size() { return lastResponse.size(); }
    }
}
