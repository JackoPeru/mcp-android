package com.example.androidmcp;

import android.net.Network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
    private volatile Network wifiNetwork;
    private volatile String token;

    public synchronized void start(TransportEndpoint lan, Network wifiNetwork, String bearerToken) throws IOException {
        if (lan == null || !"lan".equals(lan.transport) || lan.prefixLength < 1 || lan.prefixLength > 30) {
            throw new IllegalArgumentException("Valid LAN endpoint required");
        }
        if (wifiNetwork == null) throw new IllegalArgumentException("Wi-Fi network required");
        if (!SecurityValidators.isValidToken(bearerToken)) {
            throw new IllegalArgumentException("Valid discovery bearer required");
        }
        if (running.get() && lan.equals(endpoint) && wifiNetwork.equals(this.wifiNetwork)
                && bearerToken.equals(token)) return;
        stop();
        DatagramSocket candidate = new DatagramSocket(null);
        try {
            candidate.setReuseAddress(true);
            candidate.setBroadcast(true);
            // Android documents wildcard binding as the reliable way to receive
            // broadcast datagrams. Bind the socket itself to the selected Wi-Fi
            // Network so the wildcard UDP listener cannot roam onto VPN/cellular.
            wifiNetwork.bindSocket(candidate);
            candidate.bind(new InetSocketAddress(InetAddress.getByName(bindAddress(lan)), PORT));
            endpoint = lan;
            this.wifiNetwork = wifiNetwork;
            token = bearerToken;
            socket = candidate;
            running.set(true);
            Thread thread = new Thread(this::loop, "android-mcp-lan-discovery");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException | RuntimeException e) {
            candidate.close();
            endpoint = null;
            this.wifiNetwork = null;
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
        wifiNetwork = null;
        token = null;
        if (current != null) current.close();
    }

    public boolean isRunning() { return running.get(); }

    static String bindAddress(TransportEndpoint lan) {
        if (lan == null || !"lan".equals(lan.transport)) {
            throw new IllegalArgumentException("Valid LAN endpoint required");
        }
        return "0.0.0.0";
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
            } catch (IOException ignored) {
                failClosed(current);
                return;
            }
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
            try { current.send(reply); }
            catch (IOException ignored) { if (!running.get()) return; }
        }
    }

    private synchronized void failClosed(DatagramSocket failed) {
        if (socket != failed) return;
        running.set(false);
        socket = null;
        endpoint = null;
        wifiNetwork = null;
        token = null;
        failed.close();
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
