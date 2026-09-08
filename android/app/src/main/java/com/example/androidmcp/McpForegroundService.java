package com.example.androidmcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

/** User-started remote session, visible notification, no automatic restart or boot receiver. */
public final class McpForegroundService extends Service {
    private static volatile McpForegroundService instance;
    private static volatile String lastError = "";
    private McpHttpServer server;
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable networkCheck = new Runnable() {
        @Override public void run() {
            try {
                if (server == null || !server.address().equals(TailscaleAddress.find(McpForegroundService.this).getHostAddress())) {
                    lastError = "VPN disconnessa: riavvia il servizio dopo aver collegato Tailscale.";
                    stopSelf();
                    return;
                }
            } catch (Exception e) { lastError = "VPN non disponibile."; stopSelf(); return; }
            handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        ShizukuBridge.initialize(this);
        instance = this;
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
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
            server = new McpHttpServer(this);
            server.start();
            lastError = "";
            handler.post(networkCheck);
        } catch (Exception e) {
            lastError = "Avvio fallito. Collega Tailscale e verifica che la porta 8765 sia libera.";
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    static void stopNow() {
        McpForegroundService current = instance;
        ShizukuBridge.disconnect();
        if (current != null) {
            if (current.server != null) current.server.stop();
            current.stopSelf();
        }
    }
    static boolean isRunning() { McpForegroundService value = instance; return value != null && value.server != null && value.server.isRunning(); }
    static String address() { McpForegroundService value = instance; return value == null || value.server == null ? "" : value.server.address(); }
    static String error() { return lastError; }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (server != null) server.stop();
        ShizukuBridge.disconnect();
        instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
