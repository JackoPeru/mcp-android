package com.example.androidmcp;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Runtime capability reporting and named privileged operations. */
public final class CapabilityRouter {
    private static final Pattern OPENAI_STYLE =
            Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{20,}\\b");
    private static final Pattern BEARER =
            Pattern.compile("(?i)(Authorization\\s*:\\s*Bearer\\s+)[A-Za-z0-9._~-]{16,}");
    private static final Set<String> LOG_LEVELS = Set.of("V", "D", "I", "W", "E", "F");

    private CapabilityRouter() { }

    static String privilegedBackend(boolean shizukuAuthorized, boolean termuxAuthorized) {
        return shizukuAuthorized ? "shizuku" : "unavailable";
    }

    static String redactLogText(String value) {
        if (value == null || value.isEmpty()) return "";
        String redacted = BEARER.matcher(value).replaceAll("$1[REDACTED]");
        return OPENAI_STYLE.matcher(redacted).replaceAll("[REDACTED]");
    }

    public static JSONObject status(Context context, FileRootStore roots) throws ApiException {
        JSONObject termux = TermuxBridge.status(context);
        JSONObject shizuku;
        try {
            shizuku = ShizukuBridge.status(context);
        } catch (ApiException e) {
            shizuku = new JSONObject();
            try {
                shizuku.put("binderAlive", false);
                shizuku.put("permission", false);
                shizuku.put("error", e.code);
            } catch (JSONException ignored) { }
        }
        boolean shizukuAuthorized = shizuku.optBoolean("permission", false);
        boolean termuxAuthorized = termux.optBoolean("permission", false);
        JSONObject features = new JSONObject();
        JSONObject result = new JSONObject();
        try {
            features.put("ui", McpAccessibilityService.active() != null);
            features.put("notifications", McpNotificationService.active() != null);
            features.put("saf", !roots.list().isEmpty());
            features.put("forceStop", shizukuAuthorized);
            features.put("logcat", shizukuAuthorized);
            features.put("webviewDetection", McpAccessibilityService.active() != null);
            features.put("webviewInspection", false);
            features.put("visualFallbackHooks", true);

            result.put("accessibility", McpAccessibilityService.active() != null);
            result.put("notificationListener", McpNotificationService.active() != null);
            result.put("safRoots", roots.list().size());
            result.put("termux", termux);
            result.put("shizuku", shizuku);
            result.put("adb", new JSONObject().put("available", false).put("required", false));
            result.put("features", features);
            result.put("privilegedNamedBackend",
                    privilegedBackend(shizukuAuthorized, termuxAuthorized));
            result.put("automaticPrivilegeFallback", false);
            result.put("flowShellAllowed", false);
            result.put("toolCategories", toolCategories());
            result.put("requirements", new JSONObject()
                    .put("agentLoop", new JSONObject()
                            .put("backend", "accessibility")
                            .put("permission", "AccessibilityService")
                            .put("available", McpAccessibilityService.active() != null))
                    .put("files", new JSONObject()
                            .put("backend", "saf")
                            .put("permission", "persisted SAF root grant")
                            .put("available", !roots.list().isEmpty()))
                    .put("notificationsMedia", new JSONObject()
                            .put("backend", "android")
                            .put("permission", "NotificationListenerService")
                            .put("available", McpNotificationService.active() != null))
                    .put("termuxShell", new JSONObject()
                            .put("backend", "termux")
                            .put("permission", "com.termux.permission.RUN_COMMAND")
                            .put("available", termuxAuthorized))
                    .put("shizuku", new JSONObject()
                            .put("backend", "shizuku")
                            .put("permission", "explicit Shizuku app grant")
                            .put("available", shizukuAuthorized)));
            result.put("operationClasses", operationClasses());
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode capabilities");
        }
    }

    private static JSONObject toolCategories() throws JSONException {
        return new JSONObject()
                .put("agentLoop", names(
                        "android_screen_context", "android_screen_diff", "android_wait_idle",
                        "android_wait_change", "android_wait_activity", "android_scroll_to",
                        "android_act_and_observe", "android_flow", "android_diagnostics"))
                .put("ui", names(
                        "android_ui_tree", "android_ui_find", "android_ui_click",
                        "android_ui_set_text", "android_ui_wait_for", "android_screenshot",
                        "android_tap", "android_double_tap", "android_long_press", "android_swipe",
                        "android_drag", "android_pinch", "android_scroll", "android_press_key",
                        "android_input_text", "android_global_action"))
                .put("appsSystem", names(
                        "android_status", "android_launch_app", "android_apps",
                        "android_app_details", "android_open_app_settings", "android_device_info",
                        "android_open_uri", "android_share_text", "android_clipboard_get",
                        "android_clipboard_set", "android_volume_get", "android_volume_set"))
                .put("notificationsMedia", names(
                        "android_notifications", "android_notification_open",
                        "android_notification_dismiss", "android_notification_reply",
                        "android_media_sessions", "android_media_action",
                        "android_events", "android_events_wait"))
                .put("files", names(
                        "android_file_roots", "android_file_list", "android_file_stat",
                        "android_file_read", "android_file_search", "android_file_write",
                        "android_file_mkdir", "android_file_rename", "android_file_move",
                        "android_file_copy", "android_file_delete"))
                .put("privileged", names(
                        "android_shell_status", "android_shell", "android_shizuku_status",
                        "android_shizuku_shell", "android_privileged_status", "android_capabilities",
                        "android_force_stop_app", "android_logcat"))
                .put("compatibility", names("android_batch"));
    }

    private static JSONObject operationClasses() throws JSONException {
        return new JSONObject()
                .put("readOnly", names(
                        "android_status", "android_screen_context", "android_screen_diff",
                        "android_wait_idle", "android_wait_change", "android_wait_activity",
                        "android_ui_tree", "android_ui_find", "android_ui_wait_for",
                        "android_screenshot", "android_apps", "android_app_details",
                        "android_clipboard_get", "android_device_info", "android_notifications",
                        "android_media_sessions", "android_volume_get", "android_events",
                        "android_events_wait", "android_shell_status", "android_shizuku_status",
                        "android_privileged_status", "android_capabilities", "android_logcat",
                        "android_diagnostics", "android_file_roots", "android_file_list",
                        "android_file_stat", "android_file_read", "android_file_search"))
                .put("mutating", names(
                        "android_ui_click", "android_ui_set_text", "android_tap",
                        "android_double_tap", "android_long_press", "android_swipe", "android_drag",
                        "android_pinch", "android_scroll", "android_scroll_to", "android_press_key",
                        "android_input_text", "android_global_action", "android_launch_app",
                        "android_open_app_settings", "android_clipboard_set", "android_open_uri",
                        "android_share_text", "android_notification_open",
                        "android_notification_dismiss", "android_notification_reply",
                        "android_media_action", "android_volume_set", "android_shell",
                        "android_shizuku_shell", "android_file_write", "android_file_mkdir",
                        "android_file_rename", "android_file_move", "android_file_copy",
                        "android_act_and_observe", "android_flow", "android_batch"))
                .put("destructive", names(
                        "android_force_stop_app", "android_file_delete"))
                .put("outcomeUncertainOnTimeout", names(
                        "android_ui_click", "android_ui_set_text", "android_tap",
                        "android_double_tap", "android_long_press", "android_swipe", "android_drag",
                        "android_pinch", "android_press_key", "android_input_text",
                        "android_global_action", "android_launch_app", "android_open_uri",
                        "android_notification_open", "android_notification_dismiss",
                        "android_notification_reply", "android_shell", "android_shizuku_shell",
                        "android_force_stop_app", "android_act_and_observe", "android_flow",
                        "android_batch"));
    }

    private static JSONArray names(String... values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    public static JSONObject forceStop(Context context, String packageName) throws ApiException {
        if (!SecurityValidators.isValidPackageName(packageName)
                || context.getPackageName().equals(packageName)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid force-stop package");
        }
        JSONObject result = ShizukuBridge.execute(
                context, "am force-stop " + packageName, "", "", 3_000);
        if (result.optBoolean("timedOut", false)) {
            throw new ApiException("TIMEOUT", "Force-stop outcome unknown");
        }
        if (!result.isNull("exitCode") && result.optInt("exitCode", -1) != 0) {
            throw new ApiException("ACTION_REJECTED", "Force-stop failed");
        }
        try {
            return new JSONObject()
                    .put("ok", true)
                    .put("packageName", packageName)
                    .put("backend", "shizuku");
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode force-stop result");
        }
    }

    public static JSONObject logcat(Context context, String packageName, String tag, String level,
                                    int lines, int sinceSeconds) throws ApiException {
        if (packageName == null) packageName = "";
        if (tag == null) tag = "";
        if (!packageName.isEmpty() && !SecurityValidators.isValidPackageName(packageName)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid logcat package");
        }
        if (!tag.isEmpty() && !tag.matches("^[A-Za-z0-9_.:-]{1,80}$")) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid logcat tag");
        }
        if (!LOG_LEVELS.contains(level) || lines < 1 || lines > 500
                || sinceSeconds < 0 || sinceSeconds > 3_600) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid logcat bounds");
        }

        int fetchLines = Math.min(1_000, Math.max(lines, lines * 2));
        StringBuilder command = new StringBuilder();
        if (!packageName.isEmpty()) {
            command.append("PID=$(pidof ").append(packageName)
                    .append(" | cut -d' ' -f1); [ -n \"$PID\" ] || exit 3; ");
        }
        command.append("logcat -d -v threadtime -t ").append(fetchLines);
        if (!packageName.isEmpty()) command.append(" --pid=\"$PID\"");
        if (!tag.isEmpty()) {
            command.append(" -s '").append(tag).append(":").append(level).append("' '*:S'");
        } else if (!"V".equals(level)) {
            command.append(" '*:").append(level).append("'");
        }

        JSONObject raw = ShizukuBridge.execute(context, command.toString(), "", "", 5_000);
        if (raw.optBoolean("timedOut", false)) throw new ApiException("TIMEOUT", "Logcat timed out");
        int exit = raw.isNull("exitCode") ? -1 : raw.optInt("exitCode", -1);
        if (exit == 3) throw new ApiException("NOT_FOUND", "Target application is not running");
        if (exit != 0) throw new ApiException("LOGCAT_FAILED", "Logcat command failed");

        String text = redactLogText(raw.optString("stdout", ""));
        if (sinceSeconds > 0) text = filterRecent(text, sinceSeconds);
        LineSlice slice = lastLines(text, lines);
        try {
            return new JSONObject()
                    .put("backend", "shizuku")
                    .put("text", slice.text)
                    .put("lineCount", slice.count)
                    .put("truncated", raw.optBoolean("truncated", false) || slice.truncated)
                    .put("packageName", packageName)
                    .put("tag", tag)
                    .put("level", level)
                    .put("sinceSeconds", sinceSeconds);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode logcat");
        }
    }

    private static String filterRecent(String text, int sinceSeconds) {
        long now = System.currentTimeMillis();
        long cutoff = now - sinceSeconds * 1_000L;
        int year = java.time.ZonedDateTime.now().getYear();
        DateTimeFormatter parser = new DateTimeFormatterBuilder()
                .appendPattern("MM-dd HH:mm:ss.SSS")
                .parseDefaulting(ChronoField.YEAR, year)
                .toFormatter(Locale.ROOT);
        StringBuilder kept = new StringBuilder();
        boolean include = true;
        for (String line : text.split("\\R", -1)) {
            if (line.length() >= 18) {
                try {
                    LocalDateTime parsed = LocalDateTime.parse(line.substring(0, 18), parser);
                    long stamp = parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if (stamp > now + 86_400_000L) {
                        stamp = parsed.minusYears(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    }
                    include = stamp >= cutoff;
                } catch (RuntimeException ignored) {
                    // Continuation or vendor-specific log line follows the previous inclusion state.
                }
            }
            if (include) {
                if (kept.length() > 0) kept.append('\n');
                kept.append(line);
            }
        }
        return kept.toString();
    }

    private static LineSlice lastLines(String text, int maxLines) {
        if (text == null || text.isEmpty()) return new LineSlice("", 0, false);
        String[] all = text.split("\\R");
        int start = Math.max(0, all.length - maxLines);
        StringBuilder output = new StringBuilder();
        for (int i = start; i < all.length; i++) {
            if (output.length() > 0) output.append('\n');
            output.append(all[i]);
        }
        return new LineSlice(output.toString(), all.length - start, start > 0);
    }

    private static final class LineSlice {
        final String text;
        final int count;
        final boolean truncated;
        LineSlice(String text, int count, boolean truncated) {
            this.text = text;
            this.count = count;
            this.truncated = truncated;
        }
    }
}
