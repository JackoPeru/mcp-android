package com.example.androidmcp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;

/** Owns independent LAN/Tailscale listeners and reconciles them from Android network callbacks. */
public final class TransportManager {
    private static final long RECONCILE_DEBOUNCE_MS = 250L;
    private static final long WATCHDOG_MS = LowPowerSessionPolicy.networkWatchdogMs();

    private final Context context;
    private final RpcDispatcher dispatcher;
    private final Handler handler;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ConnectivityManager.NetworkCallback vpnCallback;
    private boolean monitoring;
    private RpcEndpointServer lanServer;
    private RpcEndpointServer tailscaleServer;
    private TransportEndpoint lanEndpoint;
    private TransportEndpoint tailscaleEndpoint;
    private volatile String lastError = "";

    private final Runnable reconcileTask = this::reconcile;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            reconcile();
            if (monitoring) handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    public TransportManager(Context context, RpcDispatcher dispatcher) {
        this.context = context.getApplicationContext();
        this.dispatcher = dispatcher;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public synchronized void start() {
        if (monitoring) {
            reconcile();
            return;
        }
        connectivity = context.getSystemService(ConnectivityManager.class);
        monitoring = true;
        if (connectivity != null) {
            wifiCallback = callback();
            vpnCallback = callback();
            try {
                connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback, handler);
            } catch (RuntimeException e) {
                wifiCallback = null;
            }
            try {
                connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), vpnCallback, handler);
            } catch (RuntimeException e) {
                vpnCallback = null;
            }
        }
        reconcile();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
    }

    public synchronized void stop() {
        monitoring = false;
        handler.removeCallbacks(reconcileTask);
        handler.removeCallbacks(watchdog);
        if (connectivity != null && wifiCallback != null) {
            try { connectivity.unregisterNetworkCallback(wifiCallback); }
            catch (RuntimeException ignored) { }
        }
        if (connectivity != null && vpnCallback != null) {
            try { connectivity.unregisterNetworkCallback(vpnCallback); }
            catch (RuntimeException ignored) { }
        }
        wifiCallback = null;
        vpnCallback = null;
        connectivity = null;
        stopLan();
        stopTailscale();
        lastError = "";
    }

    public synchronized boolean isRunning() {
        return (lanServer != null && lanServer.isRunning())
                || (tailscaleServer != null && tailscaleServer.isRunning());
    }

    public synchronized boolean isMonitoring() { return monitoring; }

    public synchronized int activeRequests() {
        return (lanServer == null ? 0 : lanServer.activeRequests())
                + (tailscaleServer == null ? 0 : tailscaleServer.activeRequests());
    }

    public synchronized int queuedRequests() {
        return (lanServer == null ? 0 : lanServer.queuedRequests())
                + (tailscaleServer == null ? 0 : tailscaleServer.queuedRequests());
    }

    public synchronized String preferredTransport() {
        if (lanServer != null && lanServer.isRunning()) return "lan";
        if (tailscaleServer != null && tailscaleServer.isRunning()) return "tailscale";
        return "none";
    }

    public synchronized String preferredAddress() {
        if (lanServer != null && lanServer.isRunning() && lanEndpoint != null) return lanEndpoint.address;
        if (tailscaleServer != null && tailscaleServer.isRunning() && tailscaleEndpoint != null) {
            return tailscaleEndpoint.address;
        }
        return "";
    }

    public synchronized String error() { return lastError; }

    public synchronized JSONObject status() {
        try { return encodeStatus(lanEndpoint, tailscaleEndpoint, false); }
        catch (JSONException e) { return new JSONObject(); }
    }

    static JSONObject encodeStatus(TransportEndpoint lan, TransportEndpoint tailscale,
                                   boolean lanDiscovery) throws JSONException {
        JSONObject endpoints = new JSONObject();
        endpoints.put("lan", endpointJson(lan, lanDiscovery));
        endpoints.put("tailscale", endpointJson(tailscale, false));
        JSONObject result = new JSONObject();
        result.put("endpoints", endpoints);
        result.put("preferredTransport", lan != null ? "lan" : tailscale != null ? "tailscale" : "none");
        result.put("networkMonitoring", "event_driven_dual");
        return result;
    }

    private static JSONObject endpointJson(TransportEndpoint endpoint, boolean discovery) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("available", endpoint != null);
        result.put("address", endpoint == null ? "" : endpoint.address);
        result.put("port", endpoint == null ? McpHttpServer.PORT : endpoint.port);
        if (endpoint != null && "lan".equals(endpoint.transport)) {
            result.put("prefixLength", endpoint.prefixLength);
            result.put("discovery", discovery);
        }
        return result;
    }

    private ConnectivityManager.NetworkCallback callback() {
        return new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { scheduleReconcile(); }
            @Override public void onLost(Network network) { scheduleReconcile(); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                scheduleReconcile();
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                scheduleReconcile();
            }
        };
    }

    private void scheduleReconcile() {
        handler.removeCallbacks(reconcileTask);
        handler.postDelayed(reconcileTask, RECONCILE_DEBOUNCE_MS);
    }

    synchronized void reconcile() {
        if (!monitoring) return;
        TransportEndpoint desiredLan = discoverLan();
        TransportEndpoint desiredTailscale = discoverTailscale();
        TransportReconciliation.Plan plan = TransportReconciliation.reconcile(
                lanEndpoint, tailscaleEndpoint, desiredLan, desiredTailscale);

        if (plan.stopLan || plan.restartLan) stopLan();
        if (plan.stopTailscale || plan.restartTailscale) stopTailscale();

        String error = "";
        if (plan.startLan || plan.restartLan) {
            try { startLan(desiredLan); }
            catch (Exception e) { error = "LAN listener unavailable"; }
        }
        if (plan.startTailscale || plan.restartTailscale) {
            try { startTailscale(desiredTailscale); }
            catch (Exception e) { error = error.isEmpty() ? "Tailscale listener unavailable" : error + "; Tailscale listener unavailable"; }
        }
        lastError = error;
    }

    private TransportEndpoint discoverLan() {
        ConnectivityManager manager = connectivity;
        if (manager == null) return null;
        try {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties properties = manager.getLinkProperties(network);
                TransportEndpoint endpoint = lanFrom(properties);
                if (endpoint != null) return endpoint;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private TransportEndpoint discoverTailscale() {
        ConnectivityManager manager = connectivity;
        if (manager == null) return null;
        try {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties properties = manager.getLinkProperties(network);
                TransportEndpoint endpoint = tailscaleFrom(properties);
                if (endpoint != null) return endpoint;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static TransportEndpoint lanFrom(LinkProperties properties) {
        if (properties == null) return null;
        for (LinkAddress link : properties.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            if (!(address instanceof Inet4Address)) continue;
            String ip = address.getHostAddress();
            int prefix = link.getPrefixLength();
            try {
                if (NetworkAddressPolicy.isRfc1918(ip) && prefix >= 1 && prefix <= 30) {
                    return new TransportEndpoint("lan", ip, McpHttpServer.PORT, prefix);
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    private static TransportEndpoint tailscaleFrom(LinkProperties properties) {
        if (properties == null) return null;
        for (LinkAddress link : properties.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            if (!(address instanceof Inet4Address)) continue;
            String ip = address.getHostAddress();
            try {
                if (NetworkAddressPolicy.isTailscale(ip)) {
                    return new TransportEndpoint("tailscale", ip, McpHttpServer.PORT, link.getPrefixLength());
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    private void startLan(TransportEndpoint endpoint) throws Exception {
        if (endpoint == null) return;
        RpcEndpointServer server = new RpcEndpointServer(context, dispatcher, endpoint,
                RpcEndpointServer.lanClientPolicy(endpoint.address, endpoint.prefixLength));
        server.start();
        lanServer = server;
        lanEndpoint = endpoint;
    }

    private void startTailscale(TransportEndpoint endpoint) throws Exception {
        if (endpoint == null) return;
        RpcEndpointServer server = new RpcEndpointServer(context, dispatcher, endpoint,
                RpcEndpointServer.tailscaleClientPolicy());
        server.start();
        tailscaleServer = server;
        tailscaleEndpoint = endpoint;
    }

    private void stopLan() {
        if (lanServer != null) lanServer.stop();
        lanServer = null;
        lanEndpoint = null;
    }

    private void stopTailscale() {
        if (tailscaleServer != null) tailscaleServer.stop();
        tailscaleServer = null;
        tailscaleEndpoint = null;
    }
}
