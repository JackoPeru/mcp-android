package com.example.androidmcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/** Executes a strict, bounded, shell-free UI automation flow entirely on the phone. */
public final class FlowRuntime {
    private static final int MAX_TRACE_RESULT_CHARS = 16_000;

    private final RpcDispatcher dispatcher;
    private final McpAccessibilityService service;
    private final ScreenSnapshotStore snapshots;
    private final UiLoopEngine loop;
    private ScreenSnapshotStore.Snapshot lastSnapshot;

    public FlowRuntime(RpcDispatcher dispatcher, McpAccessibilityService service,
                       ScreenSnapshotStore snapshots) {
        this.dispatcher = dispatcher;
        this.service = service;
        this.snapshots = snapshots;
        this.loop = new UiLoopEngine(service, snapshots);
    }

    public JSONObject execute(JSONObject flow) throws ApiException {
        FlowValidation.validate(flow);
        JSONArray steps = JsonArgs.requiredArray(flow, "steps");
        long timeoutMs = JsonArgs.optionalLong(flow, "timeoutMs", 18_000);
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        JSONArray traces = new JSONArray();
        JSONObject captures = new JSONObject();
        boolean ok = true;
        boolean timedOut = false;

        for (int i = 0; i < steps.length(); i++) {
            RequestScope.checkCurrent();
            if (System.nanoTime() >= deadline) {
                timedOut = true;
                ok = false;
                break;
            }
            JSONObject step = steps.optJSONObject(i);
            long stepStarted = System.nanoTime();
            String type = step.optString("type", "");
            try {
                if (!guardAllows(step)) {
                    JSONObject trace = stepTrace(i, type, elapsedMs(stepStarted), true, "");
                    trace.put("status", "skipped");
                    traces.put(trace);
                    TraceJournal.add("flow_step", type, elapsedMs(stepStarted), "skipped", "");
                    continue;
                }

                JSONObject params = boundedParams(type, JsonArgs.optionalObject(step, "params"), deadline);
                Object result = executeStep(type, params);
                if (JsonArgs.optionalBoolean(step, "observeAfter", false) && !"observe".equals(type)) {
                    lastSnapshot = loop.capture();
                } else {
                    updateLastSnapshot(result);
                }

                String capture = JsonArgs.optionalStringAllowEmpty(step, "capture", "", 32);
                if (!capture.isEmpty()) captures.put(capture, captureValue(type, result));

                JSONObject trace = stepTrace(i, type, elapsedMs(stepStarted), true, "");
                addTraceResult(trace, result);
                if (lastSnapshot != null && JsonArgs.optionalBoolean(step, "observeAfter", false)) {
                    trace.put("snapshotId", lastSnapshot.id);
                    trace.put("uiHash", lastSnapshot.uiHash);
                }
                traces.put(trace);
                TraceJournal.add("flow_step", type, elapsedMs(stepStarted), "ok", "");
            } catch (ApiException e) {
                ok = false;
                long duration = elapsedMs(stepStarted);
                traces.put(stepTrace(i, type, duration, false, e.code));
                TraceJournal.add("flow_step", type, duration, "error", e.code);
                if ("TIMEOUT".equals(e.code) || "WAIT_TIMEOUT".equals(e.code)) {
                    timedOut = System.nanoTime() >= deadline;
                }
                if (!"continue".equals(step.optString("onError", "stop"))) break;
            } catch (JSONException e) {
                throw new ApiException("INTERNAL", "Unable to encode flow trace");
            }
        }

        try {
            JSONObject result = new JSONObject()
                    .put("ok", ok)
                    .put("timedOut", timedOut)
                    .put("elapsedMs", elapsedMs(started))
                    .put("steps", traces)
                    .put("captures", captures)
                    .put("completedSteps", traces.length());
            TraceJournal.add("flow", "flow", elapsedMs(started),
                    ok ? "ok" : (timedOut ? "timeout" : "error"), "");
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode flow result");
        }
    }

    private boolean guardAllows(JSONObject step) throws ApiException {
        if (step.has("ifPresent")) {
            return service.find(JsonArgs.requiredObject(step, "ifPresent"), 1).optInt("count", 0) > 0;
        }
        if (step.has("ifAbsent")) {
            return service.find(JsonArgs.requiredObject(step, "ifAbsent"), 1).optInt("count", 0) == 0;
        }
        return true;
    }

    private Object executeStep(String type, JSONObject params) throws ApiException {
        switch (type) {
            case "find":
                return dispatcher.dispatchAllowed("ui_find", params);
            case "click":
                return dispatcher.dispatchAllowed("ui_click", params);
            case "set_text":
                return dispatcher.dispatchAllowed("ui_set_text", params);
            case "tap":
            case "double_tap":
            case "long_press":
            case "swipe":
            case "drag":
            case "pinch":
            case "scroll":
            case "press_key":
            case "launch_app":
            case "global_action":
                return dispatcher.dispatchAllowed(type, params);
            case "scroll_to":
                return dispatcher.dispatchAllowed("scroll_to", params);
            case "wait_idle":
                return dispatcher.dispatchAllowed("wait_idle", params);
            case "wait_change":
                if (!params.has("snapshotId") && !params.has("uiHash") && lastSnapshot != null) {
                    try { params.put("snapshotId", lastSnapshot.id); }
                    catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to bind snapshot"); }
                }
                return dispatcher.dispatchAllowed("wait_change", params);
            case "wait_selector":
                return dispatcher.dispatchAllowed("ui_wait_for", params);
            case "assert_selector": {
                JSONObject lookup = copy(params);
                try { lookup.put("limit", 1); }
                catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to assert selector"); }
                JSONObject found = (JSONObject) dispatcher.dispatchAllowed("ui_find", lookup);
                if (found.optInt("count", 0) == 0) {
                    throw new ApiException("ASSERTION_FAILED", "Expected UI selector is absent");
                }
                return found;
            }
            case "assert_package": {
                JsonArgs.only(params, "packageName", "windowClass");
                String expected = JsonArgs.requiredString(params, "packageName", SecurityValidators.MAX_PACKAGE_LENGTH);
                String expectedWindow = JsonArgs.optionalStringAllowEmpty(params, "windowClass", "", 512);
                lastSnapshot = loop.capture();
                String actual = lastSnapshot.context.optString("packageName", "");
                String actualWindow = lastSnapshot.context.optString("windowClass", "");
                if (!expected.equals(actual) || (!expectedWindow.isEmpty() && !expectedWindow.equals(actualWindow))) {
                    throw new ApiException("ASSERTION_FAILED", "Foreground package/window does not match");
                }
                return lastSnapshot.responseCopy();
            }
            case "observe": {
                JsonArgs.only(params, "includeInvisible", "maxNodes");
                boolean includeInvisible = JsonArgs.optionalBoolean(params, "includeInvisible", false);
                int maxNodes = (int) JsonArgs.optionalLong(params, "maxNodes", 250);
                lastSnapshot = snapshots.capture(service.compactContext(includeInvisible, maxNodes));
                return lastSnapshot.responseCopy();
            }
            default:
                throw new ApiException("INVALID_ARGUMENT", "Unsupported flow step");
        }
    }

    private static JSONObject boundedParams(String type, JSONObject params, long deadline)
            throws ApiException {
        JSONObject copy = copy(params);
        long remaining = Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        if (remaining <= 0) throw new ApiException("TIMEOUT", "Flow deadline reached");
        long defaultTimeout = -1;
        switch (type) {
            case "wait_idle":
            case "wait_change":
            case "wait_selector":
                defaultTimeout = 5_000; break;
            case "scroll_to":
                defaultTimeout = 8_000; break;
            default:
                break;
        }
        if (defaultTimeout >= 0) {
            long requested = JsonArgs.optionalLong(copy, "timeoutMs", defaultTimeout);
            try { copy.put("timeoutMs", Math.max(0, Math.min(requested, remaining))); }
            catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to bound flow timeout"); }
        }
        return copy;
    }

    private void updateLastSnapshot(Object result) throws ApiException {
        if (!(result instanceof JSONObject)) return;
        JSONObject object = (JSONObject) result;
        long snapshotId = object.optLong("snapshotId", 0);
        if (snapshotId > 0) lastSnapshot = snapshots.get(snapshotId);
    }

    private static Object captureValue(String type, Object result) throws ApiException {
        if (!(result instanceof JSONObject)) return result == null ? JSONObject.NULL : result;
        JSONObject object = (JSONObject) result;
        try {
            if ("find".equals(type)) {
                JSONArray matches = object.optJSONArray("matches");
                return matches != null && matches.length() > 0 ? matches.get(0) : JSONObject.NULL;
            }
            if ("observe".equals(type) || object.has("snapshotId")) {
                JSONObject compact = new JSONObject();
                if (object.has("snapshotId")) compact.put("snapshotId", object.get("snapshotId"));
                if (object.has("uiHash")) compact.put("uiHash", object.get("uiHash"));
                if (compact.length() > 0) return compact;
            }
            return new JSONObject(object.toString());
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to capture flow value");
        }
    }

    private static void addTraceResult(JSONObject trace, Object result) throws JSONException {
        if (result == null) return;
        String encoded = String.valueOf(result);
        if (encoded.length() <= MAX_TRACE_RESULT_CHARS) trace.put("result", result);
        else trace.put("resultTruncated", true);
    }

    static JSONObject stepTrace(int index, String type, long durationMs, boolean ok, String error)
            throws ApiException {
        try {
            JSONObject trace = new JSONObject()
                    .put("index", index)
                    .put("type", type)
                    .put("durationMs", Math.max(0, durationMs))
                    .put("status", ok ? "ok" : "error");
            if (!ok && error != null && !error.isEmpty()) trace.put("error", error);
            return trace;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode flow step");
        }
    }

    private static JSONObject copy(JSONObject source) throws ApiException {
        try { return new JSONObject(source.toString()); }
        catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to copy flow parameters"); }
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
