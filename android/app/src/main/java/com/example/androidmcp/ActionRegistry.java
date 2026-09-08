package com.example.androidmcp;

import java.util.Set;

/** Allowlist for actions that may run inside composite UI loops and flows. */
public final class ActionRegistry {
    private static final Set<String> ALLOWED = Set.of(
            "ui_click",
            "ui_set_text",
            "tap",
            "double_tap",
            "long_press",
            "swipe",
            "drag",
            "pinch",
            "scroll",
            "press_key",
            "input_text",
            "global_action",
            "launch_app",
            "open_app_settings",
            "clipboard_set",
            "media_action",
            "volume_set"
    );

    private ActionRegistry() { }

    public static boolean isAllowed(String method) {
        return method != null && ALLOWED.contains(method);
    }

    public static void requireAllowed(String method) throws ApiException {
        if (!isAllowed(method)) {
            throw new ApiException("INVALID_ARGUMENT", "Action is not allowed in a composite loop");
        }
    }
}
