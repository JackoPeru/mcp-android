package com.example.androidmcp;

import java.util.Objects;

// Kept as POJO (not a record): callers in TransportManager/McpHttpServer read
// public fields (endpoint.address); a record's endpoint.address() accessors would
// require touching those files, owned by another agent.
public final class TransportEndpoint {
    public final String transport;
    public final String address;
    public final int port;
    public final int prefixLength;

    public TransportEndpoint(String transport, String address, int port, int prefixLength) {
        if (!"lan".equals(transport) && !"tailscale".equals(transport)) {
            throw new IllegalArgumentException("Invalid transport");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        if (prefixLength < 0 || prefixLength > 32) throw new IllegalArgumentException("Invalid prefix");
        if ("lan".equals(transport) && !NetworkAddressPolicy.isRfc1918(address)) {
            throw new IllegalArgumentException("Invalid LAN address");
        }
        if ("tailscale".equals(transport) && !NetworkAddressPolicy.isTailscale(address)) {
            throw new IllegalArgumentException("Invalid Tailscale address");
        }
        this.transport = transport;
        this.address = address;
        this.port = port;
        this.prefixLength = prefixLength;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TransportEndpoint)) return false;
        TransportEndpoint that = (TransportEndpoint) other;
        return port == that.port && prefixLength == that.prefixLength
                && Objects.equals(transport, that.transport) && Objects.equals(address, that.address);
    }

    @Override public int hashCode() {
        return Objects.hash(transport, address, port, prefixLength);
    }
}
