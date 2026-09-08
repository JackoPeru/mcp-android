package com.example.androidmcp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Android APIs that do not need Accessibility node traversal. */
public final class AndroidSystemTools {
    private AndroidSystemTools() { }

    public static JSONObject apps(Context context, String query, int limit) throws ApiException {
        if (query == null || query.length() > 100 || limit < 1 || limit > 500)
            throw new ApiException("INVALID_ARGUMENT", "Invalid app query");
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = new ArrayList<>(pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL));
        infos.sort(Comparator.comparing(info -> String.valueOf(info.loadLabel(pm)), String.CASE_INSENSITIVE_ORDER));
        String needle = query.toLowerCase(Locale.ROOT);
        JSONArray apps = new JSONArray();
        try {
            for (ResolveInfo info : infos) {
                RequestScope.checkCurrent();
                if (info.activityInfo == null) continue;
                String label = String.valueOf(info.loadLabel(pm));
                String pkg = info.activityInfo.packageName;
                if (!needle.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(needle)
                        && !pkg.toLowerCase(Locale.ROOT).contains(needle)) continue;
                JSONObject item = new JSONObject();
                item.put("label", label);
                item.put("packageName", pkg);
                item.put("activity", info.activityInfo.name);
                apps.put(item);
                if (apps.length() >= limit) break;
            }
            JSONObject result = new JSONObject();
            result.put("apps", apps);
            result.put("count", apps.length());
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode applications");
        }
    }

    public static JSONObject clipboardGet(Context context) throws ApiException {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) throw new ApiException("CLIPBOARD_UNAVAILABLE", "Clipboard unavailable");
        try {
            if (!clipboard.hasPrimaryClip()) return textResult("");
            ClipData data = clipboard.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) return textResult("");
            CharSequence text = data.getItemAt(0).coerceToText(context);
            String value = text == null ? "" : text.toString();
            if (value.length() > SecurityValidators.MAX_TEXT_LENGTH)
                value = value.substring(0, SecurityValidators.MAX_TEXT_LENGTH);
            return textResult(value);
        } catch (SecurityException e) {
            throw new ApiException("CLIPBOARD_DENIED", "Android denied clipboard read");
        }
    }

    public static JSONObject clipboardSet(Context context, String text) throws ApiException {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) throw new ApiException("CLIPBOARD_UNAVAILABLE", "Clipboard unavailable");
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("MCP Android", text));
            return ok();
        } catch (RuntimeException e) {
            throw new ApiException("CLIPBOARD_DENIED", "Android denied clipboard write");
        }
    }

    public static JSONObject deviceInfo(Context context) throws ApiException {
        JSONObject result = new JSONObject();
        try {
            result.put("manufacturer", Build.MANUFACTURER);
            result.put("model", Build.MODEL);
            result.put("device", Build.DEVICE);
            result.put("androidRelease", Build.VERSION.RELEASE);
            result.put("sdk", Build.VERSION.SDK_INT);
            result.put("locale", Locale.getDefault().toLanguageTag());

            BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (battery != null) {
                result.put("batteryPercent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                result.put("charging", battery.isCharging());
            }
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) result.put("powerSave", power.isPowerSaveMode());

            StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            result.put("storageBytesTotal", stat.getTotalBytes());
            result.put("storageBytesFree", stat.getAvailableBytes());

            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            String transport = "none";
            if (cm != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "wifi";
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "cellular";
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "ethernet";
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transport = "vpn";
                    else transport = "other";
                    result.put("internet", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                }
            }
            result.put("networkTransport", transport);
            result.put("notificationAccess", McpNotificationService.active() != null);
            result.put("accessibility", McpAccessibilityService.active() != null);
            result.put("termux", packageInstalled(context, "com.termux"));
            result.put("termuxRunCommandPermission",
                    context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED);
            result.put("shizukuInstalled", packageInstalled(context, "moe.shizuku.privileged.api"));
            result.put("volumes", volumes(context));
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode device info");
        }
    }

    public static JSONObject openUri(Context context, String raw) throws ApiException {
        if (raw == null || raw.length() > SecurityValidators.MAX_URI_LENGTH)
            throw new ApiException("INVALID_ARGUMENT", "Invalid URI");
        Uri uri;
        try { uri = Uri.parse(raw); }
        catch (RuntimeException e) { throw new ApiException("INVALID_ARGUMENT", "Invalid URI"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        Intent intent;
        switch (scheme) {
            case "http":
            case "https":
            case "geo":
                intent = new Intent(Intent.ACTION_VIEW, uri);
                break;
            case "tel":
                intent = new Intent(Intent.ACTION_DIAL, uri);
                break;
            case "mailto":
            case "sms":
            case "smsto":
                intent = new Intent(Intent.ACTION_SENDTO, uri);
                break;
            default:
                throw new ApiException("INVALID_ARGUMENT", "URI scheme is not allowed");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(intent); return ok(); }
        catch (RuntimeException e) { throw new ApiException("ACTION_REJECTED", "No app accepted URI"); }
    }

    public static JSONObject shareText(Context context, String text, String title) throws ApiException {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        Intent chooser = Intent.createChooser(share, title == null || title.isEmpty() ? "Condividi" : title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(chooser); return ok(); }
        catch (RuntimeException e) { throw new ApiException("ACTION_REJECTED", "Share UI unavailable"); }
    }

    public static JSONObject volumes(Context context) throws ApiException {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) throw new ApiException("AUDIO_UNAVAILABLE", "Audio service unavailable");
        JSONObject result = new JSONObject();
        try {
            addVolume(result, audio, "media", AudioManager.STREAM_MUSIC);
            addVolume(result, audio, "ring", AudioManager.STREAM_RING);
            addVolume(result, audio, "alarm", AudioManager.STREAM_ALARM);
            addVolume(result, audio, "notification", AudioManager.STREAM_NOTIFICATION);
            addVolume(result, audio, "call", AudioManager.STREAM_VOICE_CALL);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode audio state");
        }
    }

    public static JSONObject setVolume(Context context, String streamName, int level) throws ApiException {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) throw new ApiException("AUDIO_UNAVAILABLE", "Audio service unavailable");
        int stream = stream(streamName);
        int max = audio.getStreamMaxVolume(stream);
        if (level < 0 || level > max) throw new ApiException("INVALID_ARGUMENT", "Volume outside stream range");
        try {
            audio.setStreamVolume(stream, level, 0);
            return volumes(context);
        } catch (SecurityException e) {
            throw new ApiException("AUDIO_DENIED", "Android denied volume change");
        }
    }

    public static JSONObject mediaSessions(Context context) throws ApiException {
        McpNotificationService listener = McpNotificationService.active();
        if (listener == null) throw new ApiException("NOTIFICATION_ACCESS_DISABLED", "Enable notification access first");
        MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (manager == null) throw new ApiException("MEDIA_UNAVAILABLE", "Media session service unavailable");
        List<MediaController> controllers;
        try {
            controllers = manager.getActiveSessions(new ComponentName(context, McpNotificationService.class));
        } catch (SecurityException e) {
            throw new ApiException("NOTIFICATION_ACCESS_DISABLED", "Notification access is required");
        }
        JSONArray items = new JSONArray();
        try {
            for (MediaController controller : controllers) {
                JSONObject item = new JSONObject();
                item.put("packageName", controller.getPackageName());
                if (controller.getPlaybackState() != null)
                    item.put("playbackState", controller.getPlaybackState().getState());
                if (controller.getMetadata() != null) {
                    CharSequence title = controller.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
                    if (title != null) item.put("title", cap(title.toString(), 512));
                }
                items.put(item);
            }
            JSONObject result = new JSONObject();
            result.put("sessions", items);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode media sessions");
        }
    }

    public static JSONObject mediaAction(Context context, String packageName, String action) throws ApiException {
        McpNotificationService listener = McpNotificationService.active();
        if (listener == null) throw new ApiException("NOTIFICATION_ACCESS_DISABLED", "Enable notification access first");
        MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (manager == null) throw new ApiException("MEDIA_UNAVAILABLE", "Media session service unavailable");
        List<MediaController> controllers;
        try { controllers = manager.getActiveSessions(new ComponentName(context, McpNotificationService.class)); }
        catch (SecurityException e) { throw new ApiException("NOTIFICATION_ACCESS_DISABLED", "Notification access is required"); }
        MediaController selected = null;
        for (MediaController controller : controllers) {
            if (packageName == null || packageName.isEmpty() || packageName.equals(controller.getPackageName())) {
                selected = controller;
                break;
            }
        }
        if (selected == null) throw new ApiException("NOT_FOUND", "Media session not found");
        MediaController.TransportControls controls = selected.getTransportControls();
        switch (action) {
            case "play": controls.play(); break;
            case "pause": controls.pause(); break;
            case "toggle":
                if (selected.getPlaybackState() != null
                        && selected.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PLAYING)
                    controls.pause(); else controls.play();
                break;
            case "next": controls.skipToNext(); break;
            case "previous": controls.skipToPrevious(); break;
            case "stop": controls.stop(); break;
            default: throw new ApiException("INVALID_ARGUMENT", "Invalid media action");
        }
        return ok();
    }

    public static JSONObject privilegedStatus(Context context) throws ApiException {
        JSONObject result = new JSONObject();
        try {
            result.put("termuxInstalled", packageInstalled(context, "com.termux"));
            result.put("termuxRunCommandPermission",
                    context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED);
            result.put("termux", TermuxBridge.status(context));
            try {
                result.put("shizuku", ShizukuBridge.status(context));
            } catch (ApiException e) {
                JSONObject unavailable = new JSONObject();
                unavailable.put("binderAlive", false);
                unavailable.put("error", e.code);
                result.put("shizuku", unavailable);
            }
            result.put("shellBackends", new JSONArray().put("termux").put("shizuku"));
            result.put("automaticPrivilegeFallback", false);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode privileged status");
        }
    }

    private static void addVolume(JSONObject object, AudioManager audio, String name, int stream)
            throws JSONException {
        JSONObject state = new JSONObject();
        state.put("level", audio.getStreamVolume(stream));
        state.put("min", audio.getStreamMinVolume(stream));
        state.put("max", audio.getStreamMaxVolume(stream));
        object.put(name, state);
    }

    private static int stream(String name) throws ApiException {
        switch (name) {
            case "media": return AudioManager.STREAM_MUSIC;
            case "ring": return AudioManager.STREAM_RING;
            case "alarm": return AudioManager.STREAM_ALARM;
            case "notification": return AudioManager.STREAM_NOTIFICATION;
            case "call": return AudioManager.STREAM_VOICE_CALL;
            default: throw new ApiException("INVALID_ARGUMENT", "Invalid audio stream");
        }
    }

    private static boolean packageInstalled(Context context, String packageName) {
        try { context.getPackageManager().getPackageInfo(packageName, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private static JSONObject ok() throws ApiException {
        JSONObject result = new JSONObject();
        try { result.put("ok", true); return result; }
        catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to encode result"); }
    }

    private static JSONObject textResult(String text) throws ApiException {
        JSONObject result = new JSONObject();
        try { result.put("text", text); return result; }
        catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to encode clipboard"); }
    }

    private static String cap(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
