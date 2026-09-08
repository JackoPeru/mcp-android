package com.example.androidmcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

/** User-started remote session, visible notification, no automatic restart or boot receiver. */
public final class McpForegroundService extends Service {
    private static final long VPN_EVENT_DEBOUNCE_MS = 250L;
    private static final long NETWORK_WATCHDOG_MS = LowPowerSessionPolicy.networkWatchdogMs();
    private static volatile McpForegroundService instance;
    private static volatile String lastError = "";
    private static volatile boolean sessionEnabled;
    private McpHttpServer server;
    private volatile boolean reconnecting;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback vpnCallback;
    private boolean networkMonitoringStarted;
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable networkReconcile = this::reconcileNetwork;
    private final Runnable networkWatchdog = new Runnable() {
        @Override public void run() {
            reconcileNetwork();
            handler.postDelayed(this, NETWORK_WATCHDOG_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) {
            enterLowPowerIdle();
            stopSelf();
            return START_NOT_STICKY;
        }
        sessionEnabled = true;
        McpAccessibilityService.setRemoteSessionActive(true);
        McpNotificationService.resumeForSession(this);
        if (server != null && server.isRunning()) return START_NOT_STICKY;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("remote", "Controllo remoto", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, McpForegroundService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "remote")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("MCP Android attivo")
            .setContentText("Controllo remoto autorizzato. Tocca Stop per interrompere.")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification);
        try {
            SecretStore.current(this);
            if (server == null) server = new McpHttpServer(this);
            try {
                server.start();
                reconnecting = false;
                lastError = "";
            } catch (Exception unavailable) {
                reconnecting = true;
                lastError = "Tailscale non disponibile: riconnessione automatica in corso.";
            }
            startNetworkMonitoring();
        } catch (Exception e) {
            lastError = "Avvio fallito: impossibile inizializzare il controllo remoto.";
            enterLowPowerIdle();
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    static void stopNow() {
        McpForegroundService current = instance;
        ShizukuBridge.disconnect();
        if (current != null) {
            current.enterLowPowerIdle();
            if (current.server != null) current.server.stop();
            current.stopSelf();
        } else {
            sessionEnabled = false;
            McpAccessibilityService.setRemoteSessionActive(false);
            McpNotificationService.suspendForIdle();
        }
    }
    static boolean isRunning() { McpForegroundService value = instance; return value != null && value.server != null && value.server.isRunning(); }
    static boolean sessionEnabled() { return sessionEnabled; }
    static String address() { McpForegroundService value = instance; return value == null || value.server == null ? "" : value.server.address(); }
    static String error() { return lastError; }
    static String state() {
        McpForegroundService value = instance;
        if (value == null) return "stopped";
        if (value.server != null && value.server.isRunning()) return "running";
        return "reconnecting";
    }
    static int activeRequests() {
        McpForegroundService value = instance;
        return value == null || value.server == null ? 0 : value.server.activeRequests();
    }
    static int queuedRequests() {
        McpForegroundService value = instance;
        return value == null || value.server == null ? 0 : value.server.queuedRequests();
    }
    static String networkMonitoringMode() {
        McpForegroundService value = instance;
        return value != null && value.networkMonitoringStarted ? "event_driven_vpn" : "inactive";
    }

    private void startNetworkMonitoring() {
        if (networkMonitoringStarted) return;
        connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity != null) {
            vpnCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { scheduleNetworkReconcile(); }
                @Override public void onLost(Network network) { scheduleNetworkReconcile(); }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    scheduleNetworkReconcile();
                }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    scheduleNetworkReconcile();
                }
            };
            try {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                        .build();
                connectivity.registerNetworkCallback(request, vpnCallback, handler);
            } catch (RuntimeException e) {
                vpnCallback = null;
            }
        }
        networkMonitoringStarted = true;
        handler.removeCallbacks(networkWatchdog);
        handler.postDelayed(networkWatchdog, NETWORK_WATCHDOG_MS);
    }

    private void scheduleNetworkReconcile() {
        handler.removeCallbacks(networkReconcile);
        handler.postDelayed(networkReconcile, VPN_EVENT_DEBOUNCE_MS);
    }

    private void reconcileNetwork() {
        McpHttpServer current = server;
        String discovered = "";
        try {
            discovered = TailscaleAddress.find(this).getHostAddress();
        } catch (Exception ignored) {
            // Empty address means the VPN is temporarily unavailable.
        }
        boolean serverRunning = current != null && current.isRunning();
        String bound = current == null ? "" : current.address();
        NetworkRecoveryPolicy.Action action =
                NetworkRecoveryPolicy.evaluate(serverRunning, bound, discovered);
        try {
            switch (action) {
                case KEEP:
                    reconnecting = false;
                    lastError = "";
                    break;
                case STOP_AND_WAIT:
                    if (current != null) current.stop();
                    reconnecting = true;
                    lastError = "Tailscale disconnesso: riconnessione automatica in corso.";
                    break;
                case WAIT:
                    reconnecting = true;
                    lastError = "Tailscale non disponibile: riconnessione automatica in corso.";
                    break;
                case START:
                    if (current == null) {
                        current = new McpHttpServer(this);
                        server = current;
                    }
                    current.start();
                    reconnecting = false;
                    lastError = "";
                    break;
                case RESTART:
                    current.stop();
                    current.start();
                    reconnecting = false;
                    lastError = "";
                    break;
            }
        } catch (Exception e) {
            if (current != null) current.stop();
            reconnecting = true;
            lastError = "Tailscale non disponibile: riconnessione automatica in corso.";
        }
    }

    @Override public void onDestroy() {
        enterLowPowerIdle();
        handler.removeCallbacksAndMessages(null);
        if (server != null) server.stop();
        ShizukuBridge.disconnect();
        reconnecting = false;
        instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void enterLowPowerIdle() {
        sessionEnabled = false;
        McpAccessibilityService.setRemoteSessionActive(false);
        McpNotificationService.suspendForIdle();
        handler.removeCallbacks(networkWatchdog);
        handler.removeCallbacks(networkReconcile);
        if (connectivity != null && vpnCallback != null) {
            try { connectivity.unregisterNetworkCallback(vpnCallback); }
            catch (RuntimeException ignored) { }
        }
        vpnCallback = null;
        connectivity = null;
        networkMonitoringStarted = false;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
