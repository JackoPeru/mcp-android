package com.example.androidmcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

/** User-started remote session, visible notification, no automatic restart or boot receiver. */
public final class McpForegroundService extends Service {
    private static volatile McpForegroundService instance;
    private static volatile String lastError = "";
    private static volatile boolean sessionEnabled;
    private TransportManager transportManager;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        transportManager = new TransportManager(this, new RpcDispatcher(this));
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
            transportManager.start();
            lastError = transportManager.error();
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
            current.stopSelf();
        } else {
            sessionEnabled = false;
            McpAccessibilityService.setRemoteSessionActive(false);
            McpNotificationService.suspendForIdle();
        }
    }
    static boolean isRunning() { McpForegroundService value = instance; return value != null && value.transportManager != null && value.transportManager.isRunning(); }
    static boolean sessionEnabled() { return sessionEnabled; }
    static String address() { McpForegroundService value = instance; return value == null || value.transportManager == null ? "" : value.transportManager.preferredAddress(); }
    static String error() {
        McpForegroundService value = instance;
        String transportError = value == null || value.transportManager == null ? "" : value.transportManager.error();
        return transportError.isEmpty() ? lastError : transportError;
    }
    static String state() {
        McpForegroundService value = instance;
        if (value == null) return "stopped";
        if (value.transportManager != null && value.transportManager.isRunning()) return "running";
        return "reconnecting";
    }
    static int activeRequests() {
        McpForegroundService value = instance;
        return value == null || value.transportManager == null ? 0 : value.transportManager.activeRequests();
    }
    static int queuedRequests() {
        McpForegroundService value = instance;
        return value == null || value.transportManager == null ? 0 : value.transportManager.queuedRequests();
    }
    static String networkMonitoringMode() {
        McpForegroundService value = instance;
        return value != null && value.transportManager != null && value.transportManager.isMonitoring()
                ? "event_driven_dual" : "inactive";
    }

    static JSONObject transportStatus() {
        McpForegroundService value = instance;
        if (value == null || value.transportManager == null) {
            try { return TransportManager.encodeStatus(null, null, false); }
            catch (org.json.JSONException e) { return new JSONObject(); }
        }
        return value.transportManager.status();
    }

    @Override public void onDestroy() {
        enterLowPowerIdle();
        ShizukuBridge.disconnect();
        instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void enterLowPowerIdle() {
        sessionEnabled = false;
        McpAccessibilityService.setRemoteSessionActive(false);
        McpNotificationService.suspendForIdle();
        if (transportManager != null) transportManager.stop();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
