package com.example.androidmcp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Concurrency policy for RPC methods. Keeps long waits/shells from blocking UI gestures.
 * Phone-side source of truth for lock domains/veil; bridge/server.js only describes methods. */
public final class RpcPolicy {
    public enum LockDomain { NONE, UI, FILE, SHELL }

    /**
     * UI-domain methods that do not touch the display: volume, media control,
     * clipboard, notification inbox management and plain app listing. They run
     * under the UI lock but must not raise the session veil.
     */
    private static final Set<String> NON_VISUAL_UI = new HashSet<>(Arrays.asList(
            "apps",
            "clipboard_get", "clipboard_set",
            "media_sessions", "media_action",
            "volume_get", "volume_set",
            "notifications", "notification_dismiss", "notification_reply",
            // The off switch itself: pulsing here would re-light the veil that
            // ui_done just faded.
            "ui_done"));

    private RpcPolicy() { }

    public static LockDomain lockDomain(String method) {
        if (method == null) return LockDomain.UI;
        if (method.startsWith("file_")) return LockDomain.FILE;
        switch (method) {
            case "status":
            case "screen_context":
            case "screen_diff":
            case "wait_idle":
            case "wait_change":
            case "wait_activity":
            case "device_info":
            case "app_details":
            case "events":
            case "events_wait":
            case "shell_status":
            case "shizuku_status":
            case "privileged_status":
            case "capabilities":
            case "diagnostics":
                return LockDomain.NONE;
            case "shell":
            case "shizuku_shell":
            case "force_stop_app":
            case "logcat":
                return LockDomain.SHELL;
            case "scroll_to":
                return LockDomain.UI;
            default:
                return LockDomain.UI;
        }
    }

    /**
     * Whether this RPC means the agent is looking at or touching the screen.
     * Drives the session veil: visible only while UI work happens, never for
     * shell/file/status traffic. Future UI-domain methods are visible by
     * default (fail-visible); only the allowlisted non-visual ones stay dark.
     */
    public static boolean showsVeil(String method) {
        if (method == null) return true;
        switch (method) {
            // Screen observation reads are lock-free but still mean the agent
            // is looking at the display.
            case "screen_context":
            case "screen_diff":
            case "screenshot":
            case "ui_tree":
            case "ui_find":
            case "wait_idle":
            case "wait_change":
            case "wait_activity":
                return true;
            default:
                break;
        }
        if (lockDomain(method) != LockDomain.UI) return false;
        return !NON_VISUAL_UI.contains(method);
    }
}
