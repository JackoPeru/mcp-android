package com.example.androidmcp;

import android.content.Context;

import java.io.IOException;

/** One authenticated RPC listener bound to one explicit transport endpoint. */
public final class RpcEndpointServer {
    public interface ClientPolicy {
        boolean allow(String remoteIpv4);
    }

    private final TransportEndpoint endpoint;
    private final McpHttpServer delegate;

    public RpcEndpointServer(Context context, RpcDispatcher dispatcher, TransportEndpoint endpoint,
                             ClientPolicy clientPolicy) {
        this.endpoint = endpoint;
        this.delegate = new McpHttpServer(context, dispatcher, endpoint, clientPolicy);
    }

    public static ClientPolicy lanClientPolicy(String localAddress, int prefixLength) {
        if (!NetworkAddressPolicy.isRfc1918(localAddress) || prefixLength < 1 || prefixLength > 30) {
            throw new IllegalArgumentException("Invalid LAN endpoint");
        }
        return remote -> {
            try {
                return NetworkAddressPolicy.isRfc1918(remote)
                        && NetworkAddressPolicy.subnetContains(localAddress, prefixLength, remote);
            } catch (IllegalArgumentException e) {
                return false;
            }
        };
    }

    public static ClientPolicy tailscaleClientPolicy() {
        return remote -> {
            try { return NetworkAddressPolicy.isTailscale(remote); }
            catch (IllegalArgumentException e) { return false; }
        };
    }

    public void start() throws IOException { delegate.start(); }
    public void stop() { delegate.stop(); }
    public boolean isRunning() { return delegate.isRunning(); }
    public TransportEndpoint endpoint() { return endpoint; }
    public int activeRequests() { return delegate.activeRequests(); }
    public int queuedRequests() { return delegate.queuedRequests(); }
}
