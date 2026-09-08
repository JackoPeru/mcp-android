package com.example.androidmcp;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicReference;

/** User-enabled notification bridge. Notification content is returned only on explicit RPC calls. */
public final class McpNotificationService extends NotificationListenerService {
    private static final AtomicReference<McpNotificationService> ACTIVE = new AtomicReference<>();
    private static final int MAX_TEXT = 2_048;

    public static McpNotificationService active() { return ACTIVE.get(); }

    static void resumeForSession(Context context) {
        if (!LowPowerSessionPolicy.notificationListenerActive(true)) return;
        try {
            requestRebind(new ComponentName(context.getApplicationContext(), McpNotificationService.class));
        } catch (RuntimeException ignored) {
            // Permission may not be granted; capability reporting will show it unavailable.
        }
    }

    static void suspendForIdle() {
        McpNotificationService current = ACTIVE.get();
        if (current == null) return;
        try { current.requestUnbind(); }
        catch (RuntimeException ignored) { }
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        if (!LowPowerSessionPolicy.notificationListenerActive(McpForegroundService.sessionEnabled())) {
            ACTIVE.compareAndSet(this, null);
            try { requestUnbind(); } catch (RuntimeException ignored) { }
            return;
        }
        ACTIVE.set(this);
        EventJournal.add("notification_access", getPackageName(), "connected");
    }

    @Override public void onListenerDisconnected() {
        ACTIVE.compareAndSet(this, null);
        if (McpForegroundService.sessionEnabled()) {
            EventJournal.add("notification_access", getPackageName(), "disconnected");
        }
        super.onListenerDisconnected();
    }

    @Override public void onDestroy() {
        ACTIVE.compareAndSet(this, null);
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (McpForegroundService.sessionEnabled()
                && sbn != null && !getPackageName().equals(sbn.getPackageName())) {
            EventJournal.add("notification_posted", sbn.getPackageName(), "");
        }
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (McpForegroundService.sessionEnabled()
                && sbn != null && !getPackageName().equals(sbn.getPackageName())) {
            EventJournal.add("notification_removed", sbn.getPackageName(), "");
        }
    }

    public JSONObject list(int limit) throws ApiException {
        if (limit < 1 || limit > 200) throw new ApiException("INVALID_ARGUMENT", "Invalid notification limit");
        StatusBarNotification[] active;
        try { active = getActiveNotifications(); }
        catch (SecurityException e) { throw new ApiException("NOTIFICATION_ACCESS_DISABLED", "Notification access disabled"); }
        JSONArray items = new JSONArray();
        try {
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    RequestScope.checkCurrent();
                    if (sbn == null || getPackageName().equals(sbn.getPackageName())) continue;
                    Notification n = sbn.getNotification();
                    Bundle extras = n == null ? null : n.extras;
                    JSONObject item = new JSONObject();
                    item.put("key", cap(sbn.getKey(), 512));
                    item.put("packageName", cap(sbn.getPackageName(), 200));
                    item.put("postTime", sbn.getPostTime());
                    item.put("ongoing", sbn.isOngoing());
                    if (extras != null) {
                        item.put("title", cap(string(extras.getCharSequence(Notification.EXTRA_TITLE)), MAX_TEXT));
                        item.put("text", cap(string(extras.getCharSequence(Notification.EXTRA_TEXT)), MAX_TEXT));
                        item.put("subText", cap(string(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)), MAX_TEXT));
                    }
                    item.put("canOpen", n != null && n.contentIntent != null);
                    item.put("canReply", firstReplyAction(n) != null);
                    items.put(item);
                    if (items.length() >= limit) break;
                }
            }
            JSONObject result = new JSONObject();
            result.put("notifications", items);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode notifications");
        }
    }

    public JSONObject open(String key) throws ApiException {
        StatusBarNotification sbn = find(key);
        Notification n = sbn.getNotification();
        if (n == null || n.contentIntent == null) throw new ApiException("ACTION_REJECTED", "Notification cannot be opened");
        try { n.contentIntent.send(); return ok(); }
        catch (PendingIntent.CanceledException e) { throw new ApiException("ACTION_REJECTED", "Notification action expired"); }
    }

    public JSONObject dismiss(String key) throws ApiException {
        find(key);
        try { cancelNotification(key); return ok(); }
        catch (RuntimeException e) { throw new ApiException("ACTION_REJECTED", "Notification could not be dismissed"); }
    }

    public JSONObject reply(String key, String text) throws ApiException {
        StatusBarNotification sbn = find(key);
        Notification.Action action = firstReplyAction(sbn.getNotification());
        if (action == null || action.actionIntent == null)
            throw new ApiException("ACTION_REJECTED", "Notification has no direct reply");
        RemoteInput[] inputs = action.getRemoteInputs();
        if (inputs == null || inputs.length == 0)
            throw new ApiException("ACTION_REJECTED", "Notification has no direct reply");
        Intent fillIn = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput input : inputs) results.putCharSequence(input.getResultKey(), text);
        RemoteInput.addResultsToIntent(inputs, fillIn, results);
        try { action.actionIntent.send(this, 0, fillIn); return ok(); }
        catch (PendingIntent.CanceledException e) { throw new ApiException("ACTION_REJECTED", "Reply action expired"); }
    }

    private StatusBarNotification find(String key) throws ApiException {
        if (key == null || key.isEmpty() || key.length() > 512)
            throw new ApiException("INVALID_ARGUMENT", "Invalid notification key");
        StatusBarNotification[] active;
        try { active = getActiveNotifications(); }
        catch (SecurityException e) { throw new ApiException("NOTIFICATION_ACCESS_DISABLED", "Notification access disabled"); }
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                if (sbn != null && key.equals(sbn.getKey()) && !getPackageName().equals(sbn.getPackageName()))
                    return sbn;
            }
        }
        throw new ApiException("NOT_FOUND", "Notification not found");
    }

    private static Notification.Action firstReplyAction(Notification notification) {
        if (notification == null || notification.actions == null) return null;
        for (Notification.Action action : notification.actions) {
            if (action != null && action.getRemoteInputs() != null && action.getRemoteInputs().length > 0)
                return action;
        }
        return null;
    }

    private static String string(CharSequence value) { return value == null ? "" : value.toString(); }
    private static String cap(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
    private static JSONObject ok() throws ApiException {
        JSONObject result = new JSONObject();
        try { result.put("ok", true); return result; }
        catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to encode result"); }
    }
}
