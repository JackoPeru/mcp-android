package com.example.androidmcp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

/** Phone-side trust boundary for the bounded UI flow DSL. */
public final class FlowValidation {
    public static final int MAX_STEPS = 40;
    public static final long MAX_TIMEOUT_MS = 20_000;

    private static final Set<String> TYPES = Set.of(
            "find", "click", "set_text", "tap", "double_tap", "long_press", "swipe",
            "drag", "pinch", "scroll", "scroll_to", "press_key", "launch_app",
            "global_action", "wait_idle", "wait_change", "wait_selector",
            "assert_selector", "assert_package", "observe"
    );

    private FlowValidation() { }

    public static void validate(JSONObject flow) throws ApiException {
        JsonArgs.only(flow, "steps", "timeoutMs");
        JSONArray steps = JsonArgs.requiredArray(flow, "steps");
        if (steps.length() < 1 || steps.length() > MAX_STEPS) {
            throw new ApiException("INVALID_ARGUMENT", "Flow must contain 1..40 steps");
        }
        long timeout = JsonArgs.optionalLong(flow, "timeoutMs", 18_000);
        if (timeout < 100 || timeout > MAX_TIMEOUT_MS) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid flow timeout");
        }
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) throw new ApiException("INVALID_ARGUMENT", "Flow step must be an object");
            validateStep(step);
        }
    }

    static void validateStep(JSONObject step) throws ApiException {
        JsonArgs.only(step, "type", "params", "ifPresent", "ifAbsent", "onError", "capture", "observeAfter");
        String type = JsonArgs.requiredString(step, "type", 32);
        if (!TYPES.contains(type)) throw new ApiException("INVALID_ARGUMENT", "Unsupported flow step");
        JsonArgs.optionalObject(step, "params");

        boolean hasPresent = step.has("ifPresent");
        boolean hasAbsent = step.has("ifAbsent");
        if (hasPresent && hasAbsent) {
            throw new ApiException("INVALID_ARGUMENT", "Flow step cannot use both guards");
        }
        if (hasPresent) RpcDispatcher.validateSelectorSpec(JsonArgs.requiredObject(step, "ifPresent"));
        if (hasAbsent) RpcDispatcher.validateSelectorSpec(JsonArgs.requiredObject(step, "ifAbsent"));

        String onError = JsonArgs.optionalString(step, "onError", "stop", 16);
        if (!"stop".equals(onError) && !"continue".equals(onError)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid flow error policy");
        }
        String capture = JsonArgs.optionalStringAllowEmpty(step, "capture", "", 32);
        if (!capture.isEmpty() && !capture.matches("^[A-Za-z][A-Za-z0-9_]{0,31}$")) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid flow capture name");
        }
        JsonArgs.optionalBoolean(step, "observeAfter", false);
    }
}
