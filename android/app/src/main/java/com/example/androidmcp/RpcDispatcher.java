package com.example.androidmcp;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.view.WindowManager;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Operation allowlist and parameter boundary for the authenticated HTTP server. */
public final class RpcDispatcher {
    private final Context context;
    private final FileRootStore roots;
    private final SafFileStore files;
    private final ScreenSnapshotStore snapshots = new ScreenSnapshotStore();
    private static final java.util.concurrent.locks.ReentrantLock UI = new java.util.concurrent.locks.ReentrantLock();
    private static final java.util.concurrent.locks.ReentrantLock FILE = new java.util.concurrent.locks.ReentrantLock();
    private static final java.util.concurrent.locks.ReentrantLock SHELL = new java.util.concurrent.locks.ReentrantLock();

    public RpcDispatcher(Context context) {
        this.context = context.getApplicationContext();
        roots = new FileRootStore(this.context);
        files = new SafFileStore(this.context, roots);
    }

    public Object dispatch(String method, JSONObject params) throws ApiException {
        RequestScope.checkCurrent();
        RpcPolicy.LockDomain domain = RpcPolicy.lockDomain(method);
        java.util.concurrent.locks.ReentrantLock lock = lockFor(domain);
        if (lock != null && !lock.tryLock()) {
            switch (domain) {
                case FILE:
                    throw new ApiException("BUSY", "Another file operation is active");
                case SHELL:
                    throw new ApiException("BUSY", "Another shell operation is active");
                default:
                    throw new ApiException("BUSY", "Another UI or system operation is active");
            }
        }
        try { return dispatchAllowed(method, params); }
        finally {
            if (lock != null) lock.unlock();
        }
    }

    private static java.util.concurrent.locks.ReentrantLock lockFor(RpcPolicy.LockDomain domain) {
        switch (domain) {
            case UI: return UI;
            case FILE: return FILE;
            case SHELL: return SHELL;
            default: return null;
        }
    }

    Object dispatchAllowed(String method, JSONObject params) throws ApiException {
        switch (method) {
            case "status": return status(params);
            case "screen_context": return screenContext(params);
            case "screen_diff": return screenDiff(params);
            case "wait_idle": return waitIdle(params);
            case "wait_change": return waitChange(params);
            case "wait_activity": return waitActivity(params);
            case "scroll_to": return scrollTo(params);
            case "act_and_observe": return actAndObserve(params);
            case "flow": return flow(params);
            case "ui_tree": return uiTree(params);
            case "ui_find": return uiFind(params);
            case "ui_click": return uiClick(params);
            case "ui_set_text": return uiSetText(params);
            case "ui_wait_for": return uiWaitFor(params);
            case "screenshot": return screenshot(params);
            case "tap": return tap(params);
            case "double_tap": return doubleTap(params);
            case "long_press": return longPress(params);
            case "swipe": return swipe(params);
            case "drag": return drag(params);
            case "pinch": return pinch(params);
            case "scroll": return scroll(params);
            case "press_key": return pressKey(params);
            case "input_text": return inputText(params);
            case "global_action": return globalAction(params);
            case "launch_app": return launchApp(params);
            case "apps": return apps(params);
            case "app_details": return appDetails(params);
            case "open_app_settings": return openAppSettings(params);
            case "clipboard_get": return clipboardGet(params);
            case "clipboard_set": return clipboardSet(params);
            case "device_info": return deviceInfo(params);
            case "open_uri": return openUri(params);
            case "share_text": return shareText(params);
            case "notifications": return notifications(params);
            case "notification_open": return notificationOpen(params);
            case "notification_dismiss": return notificationDismiss(params);
            case "notification_reply": return notificationReply(params);
            case "media_sessions": return mediaSessions(params);
            case "media_action": return mediaAction(params);
            case "volume_get": return volumeGet(params);
            case "volume_set": return volumeSet(params);
            case "events": return events(params);
            case "events_wait": return eventsWait(params);
            case "shell_status": return shellStatus(params);
            case "shell": return shell(params);
            case "shizuku_status": return shizukuStatus(params);
            case "shizuku_shell": return shizukuShell(params);
            case "privileged_status": return privilegedStatus(params);
            case "capabilities": return capabilities(params);
            case "force_stop_app": return forceStopApp(params);
            case "logcat": return logcat(params);
            case "file_roots": return fileRoots(params);
            case "file_list": return fileList(params);
            case "file_stat": return fileStat(params);
            case "file_read": return fileRead(params);
            case "file_search": return fileSearch(params);
            case "file_write": return fileWrite(params);
            case "file_mkdir": return fileMkdir(params);
            case "file_rename": return fileRename(params);
            case "file_move": return fileMove(params);
            case "file_copy": return fileCopy(params);
            case "file_delete": return fileDelete(params);
            default: throw new ApiException("UNKNOWN_METHOD", "Unknown RPC method");
        }
    }

    private JSONObject status(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        JSONObject result = new JSONObject();
        Point display = displaySize();
        try {
            result.put("service", "android-private-mcp");
            try {
                android.content.pm.PackageInfo packageInfo =
                        context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                result.put("versionName", packageInfo.versionName == null ? "" : packageInfo.versionName);
                result.put("versionCode", packageInfo.getLongVersionCode());
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                result.put("versionName", "");
                result.put("versionCode", 0);
            }
            result.put("updateChannel", "github-stable");
            result.put("updaterEnabled", true);
            result.put("running", McpForegroundService.isRunning());
            result.put("serviceState", McpForegroundService.state());
            result.put("address", McpForegroundService.address());
            result.put("port", McpHttpServer.PORT);
            result.put("activeRequests", McpForegroundService.activeRequests());
            result.put("queuedRequests", McpForegroundService.queuedRequests());
            result.put("requestDeadlineMs", McpHttpServer.requestDeadlineMs());
            result.put("accessibilityEnabled", McpAccessibilityService.active() != null);
            result.put("notificationAccess", McpNotificationService.active() != null);
            result.put("termuxShellAvailable",
                    context.checkSelfPermission("com.termux.permission.RUN_COMMAND")
                            == android.content.pm.PackageManager.PERMISSION_GRANTED);
            McpAccessibilityService active = McpAccessibilityService.active();
            result.put("nativeOperationInFlight", active != null && active.operationInFlight());
            result.put("locked", isLocked());
            result.put("fileRoots", roots.list().size());
            result.put("displayWidth", display.x);
            result.put("displayHeight", display.y);
            String error = McpForegroundService.error();
            if (!error.isEmpty()) {
                result.put("lastError", error);
            }
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode status");
        }
    }

    private JSONObject screenContext(JSONObject params) throws ApiException {
        JsonArgs.only(params, "treeMode", "screenshot", "includeInvisible", "maxNodes");
        requireUnlocked();
        String treeMode = JsonArgs.optionalString(params, "treeMode", "compact", 16);
        if (!treeMode.equals("compact")) throw new ApiException("INVALID_ARGUMENT", "Unsupported tree mode");
        boolean includeInvisible = JsonArgs.optionalBoolean(params, "includeInvisible", false);
        boolean includeScreenshot = JsonArgs.optionalBoolean(params, "screenshot", false);
        int maxNodes = (int) JsonArgs.optionalLong(params, "maxNodes", 250);
        McpAccessibilityService service = requireAccessibility();
        JSONObject semantic = service.compactContext(includeInvisible, maxNodes);
        ScreenSnapshotStore.Snapshot snapshot = snapshots.capture(semantic);
        JSONObject response = snapshot.responseCopy();
        if (includeScreenshot) attachScreenshot(response, service);
        return response;
    }

    private JSONObject screenDiff(JSONObject params) throws ApiException {
        JsonArgs.only(params, "fromSnapshotId", "toSnapshotId");
        long from = JsonArgs.requiredLong(params, "fromSnapshotId");
        long to = JsonArgs.requiredLong(params, "toSnapshotId");
        if (from < 1 || to < 1) throw new ApiException("INVALID_ARGUMENT", "Invalid snapshot id");
        return snapshots.diff(from, to);
    }

    private JSONObject waitIdle(JSONObject params) throws ApiException {
        JsonArgs.only(params, "timeoutMs", "quietMs");
        requireUnlocked();
        return new UiLoopEngine(requireAccessibility(), snapshots).waitIdle(
                JsonArgs.optionalLong(params, "timeoutMs", 5_000),
                JsonArgs.optionalLong(params, "quietMs", 300));
    }

    private JSONObject waitChange(JSONObject params) throws ApiException {
        JsonArgs.only(params, "snapshotId", "uiHash", "timeoutMs");
        requireUnlocked();
        long snapshotId = JsonArgs.optionalLong(params, "snapshotId", 0);
        String uiHash = JsonArgs.optionalStringAllowEmpty(params, "uiHash", "", 64);
        return new UiLoopEngine(requireAccessibility(), snapshots).waitChange(
                snapshotId, uiHash, JsonArgs.optionalLong(params, "timeoutMs", 5_000));
    }

    private JSONObject waitActivity(JSONObject params) throws ApiException {
        JsonArgs.only(params, "packageName", "windowClass", "timeoutMs");
        requireUnlocked();
        String packageName = JsonArgs.requiredString(params, "packageName", SecurityValidators.MAX_PACKAGE_LENGTH);
        if (!SecurityValidators.isValidPackageName(packageName)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid package name");
        }
        String windowClass = JsonArgs.optionalStringAllowEmpty(params, "windowClass", "", 512);
        return new UiLoopEngine(requireAccessibility(), snapshots).waitActivity(
                packageName, windowClass, JsonArgs.optionalLong(params, "timeoutMs", 5_000));
    }

    private JSONObject scrollTo(JSONObject params) throws ApiException {
        JsonArgs.only(params, "text", "textContains", "description", "descriptionContains",
                "viewId", "className", "packageName", "clickable", "editable", "enabled",
                "visible", "caseSensitive", "direction", "maxSteps", "timeoutMs");
        requireUnlocked();
        String direction = JsonArgs.optionalString(params, "direction", "down", 16);
        int maxSteps = (int) JsonArgs.optionalLong(params, "maxSteps", 8);
        long timeout = JsonArgs.optionalLong(params, "timeoutMs", 8_000);
        return new UiLoopEngine(requireAccessibility(), snapshots)
                .scrollTo(selectorFrom(params), direction, maxSteps, timeout);
    }

    private JSONObject actAndObserve(JSONObject params) throws ApiException {
        JsonArgs.only(params, "action", "wait", "observe");
        requireUnlocked();
        JSONObject action = JsonArgs.requiredObject(params, "action");
        JsonArgs.only(action, "method", "params");
        String method = JsonArgs.requiredString(action, "method", 64);
        ActionRegistry.requireAllowed(method);
        JSONObject actionParams = JsonArgs.optionalObject(action, "params");

        JSONObject wait = JsonArgs.optionalObject(params, "wait");
        validateCompositeWait(wait);
        JSONObject observe = JsonArgs.optionalObject(params, "observe");
        JsonArgs.only(observe, "mode", "screenshot");
        String observeMode = JsonArgs.optionalString(observe, "mode", "diff", 16);
        if (!observeMode.equals("diff") && !observeMode.equals("context")) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid observe mode");
        }
        boolean includeScreenshot = JsonArgs.optionalBoolean(observe, "screenshot", false);

        McpAccessibilityService service = requireAccessibility();
        UiLoopEngine loop = new UiLoopEngine(service, snapshots);
        ScreenSnapshotStore.Snapshot before = loop.capture();
        Object actionResult = null;
        boolean outcomeUnknown = false;
        String actionError = "";
        try {
            actionResult = dispatchAllowed(method, actionParams);
        } catch (ApiException e) {
            if (!"TIMEOUT".equals(e.code)) throw e;
            outcomeUnknown = true;
            actionError = e.code;
        }

        JSONObject waitResult;
        try {
            waitResult = performCompositeWait(wait, before, loop, service);
            waitResult.put("ok", true);
        } catch (ApiException e) {
            if (!"WAIT_TIMEOUT".equals(e.code) && !"TIMEOUT".equals(e.code)) throw e;
            waitResult = new JSONObject();
            try {
                waitResult.put("ok", false);
                waitResult.put("error", e.code);
            } catch (JSONException jsonError) {
                throw new ApiException("INTERNAL", "Unable to encode wait result");
            }
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode wait result");
        }

        ScreenSnapshotStore.Snapshot after = loop.capture();
        JSONObject diff = snapshots.diff(before.id, after.id);
        JSONObject result = new JSONObject();
        try {
            JSONObject actionState = new JSONObject()
                    .put("method", method)
                    .put("ok", actionError.isEmpty())
                    .put("outcomeUnknown", outcomeUnknown);
            if (!actionError.isEmpty()) actionState.put("error", actionError);
            if (actionResult != null) actionState.put("result", actionResult);
            result.put("action", actionState);
            result.put("wait", waitResult);
            result.put("beforeSnapshotId", before.id);
            result.put("afterSnapshotId", after.id);
            result.put("uiHash", after.uiHash);
            result.put("changed", diff.optBoolean("changed", false));
            if ("context".equals(observeMode)) result.put("context", after.responseCopy());
            else result.put("diff", diff);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode act-and-observe result");
        }
        if (includeScreenshot) attachScreenshot(result, service);
        return result;
    }

    private JSONObject flow(JSONObject params) throws ApiException {
        requireUnlocked();
        return new FlowRuntime(this, requireAccessibility(), snapshots).execute(params);
    }

    private static void validateCompositeWait(JSONObject wait) throws ApiException {
        JsonArgs.only(wait, "mode", "timeoutMs", "quietMs", "selector", "state", "pollMs",
                "packageName", "windowClass");
        String mode = JsonArgs.optionalString(wait, "mode", "idle", 16);
        if (!Set.of("idle", "change", "selector", "activity", "none").contains(mode)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid composite wait mode");
        }
        long timeout = JsonArgs.optionalLong(wait, "timeoutMs", 5_000);
        if (timeout < 0 || timeout > 12_000) throw new ApiException("INVALID_ARGUMENT", "Invalid wait timeout");
        if ("idle".equals(mode)) {
            long quiet = JsonArgs.optionalLong(wait, "quietMs", 300);
            if (quiet < 100 || quiet > 1_000) throw new ApiException("INVALID_ARGUMENT", "Invalid quiet window");
        }
        if ("selector".equals(mode)) {
            JSONObject selector = JsonArgs.requiredObject(wait, "selector");
            validateSelectorSpec(selector);
            String state = JsonArgs.optionalString(wait, "state", "present", 16);
            if (!state.equals("present") && !state.equals("absent")) {
                throw new ApiException("INVALID_ARGUMENT", "Invalid selector wait state");
            }
            long poll = JsonArgs.optionalLong(wait, "pollMs", 250);
            if (poll < 50 || poll > 1_000) throw new ApiException("INVALID_ARGUMENT", "Invalid selector poll");
        }
        if ("activity".equals(mode)) {
            String packageName = JsonArgs.requiredString(wait, "packageName", SecurityValidators.MAX_PACKAGE_LENGTH);
            if (!SecurityValidators.isValidPackageName(packageName)) {
                throw new ApiException("INVALID_ARGUMENT", "Invalid package name");
            }
            JsonArgs.optionalStringAllowEmpty(wait, "windowClass", "", 512);
        }
    }

    private static JSONObject performCompositeWait(JSONObject wait, ScreenSnapshotStore.Snapshot before,
                                                   UiLoopEngine loop, McpAccessibilityService service)
            throws ApiException {
        String mode = JsonArgs.optionalString(wait, "mode", "idle", 16);
        long timeout = JsonArgs.optionalLong(wait, "timeoutMs", 5_000);
        switch (mode) {
            case "none":
                return new JSONObject();
            case "idle":
                return loop.waitIdle(timeout, JsonArgs.optionalLong(wait, "quietMs", 300));
            case "change":
                return loop.waitChange(before.id, before.uiHash, timeout);
            case "selector": {
                JSONObject selector = JsonArgs.requiredObject(wait, "selector");
                boolean present = !"absent".equals(JsonArgs.optionalString(wait, "state", "present", 16));
                return service.waitFor(selector, present, timeout, JsonArgs.optionalLong(wait, "pollMs", 250));
            }
            case "activity":
                return loop.waitActivity(
                        JsonArgs.requiredString(wait, "packageName", SecurityValidators.MAX_PACKAGE_LENGTH),
                        JsonArgs.optionalStringAllowEmpty(wait, "windowClass", "", 512), timeout);
            default:
                throw new ApiException("INVALID_ARGUMENT", "Invalid composite wait mode");
        }
    }

    static void validateSelectorSpec(JSONObject selector) throws ApiException {
        JsonArgs.only(selector, "text", "textContains", "description", "descriptionContains",
                "viewId", "className", "packageName", "clickable", "editable", "enabled",
                "visible", "caseSensitive");
        boolean criterion = false;
        String[] textKeys = {"text", "textContains", "description", "descriptionContains",
                "viewId", "className", "packageName"};
        for (String key : textKeys) {
            if (selector.has(key)) {
                JsonArgs.requiredStringAllowEmpty(selector, key, SecurityValidators.MAX_SELECTOR_TEXT);
                criterion = true;
            }
        }
        String[] booleanKeys = {"clickable", "editable", "enabled", "visible"};
        for (String key : booleanKeys) {
            if (selector.has(key)) {
                JsonArgs.requiredBoolean(selector, key);
                criterion = true;
            }
        }
        JsonArgs.optionalBoolean(selector, "caseSensitive", false);
        if (!criterion) throw new ApiException("INVALID_ARGUMENT", "Selector needs at least one criterion");
    }

    private static void attachScreenshot(JSONObject target, McpAccessibilityService service) throws ApiException {
        byte[] png = service.screenshot();
        String data = Base64.encodeToString(png, Base64.NO_WRAP);
        if (data.length() > 8 * 1024 * 1024) {
            throw new ApiException("RESPONSE_TOO_LARGE", "Screenshot exceeds response limit");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, options);
        try {
            target.put("screenshot", new JSONObject()
                    .put("mimeType", "image/png")
                    .put("data", data)
                    .put("width", Math.max(1, options.outWidth))
                    .put("height", Math.max(1, options.outHeight)));
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode screenshot");
        }
    }

    private JSONObject uiTree(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        requireUnlocked();
        return requireAccessibility().uiTree();
    }

    private JSONObject uiFind(JSONObject params) throws ApiException {
        JsonArgs.only(params, "text", "textContains", "description", "descriptionContains",
                "viewId", "className", "packageName", "clickable", "editable", "enabled",
                "visible", "caseSensitive", "limit");
        requireUnlocked();
        int limit = (int) JsonArgs.optionalLong(params, "limit", 20);
        return requireAccessibility().find(selectorFrom(params), limit);
    }

    private JSONObject uiClick(JSONObject params) throws ApiException {
        JsonArgs.only(params, "text", "textContains", "description", "descriptionContains",
                "viewId", "className", "packageName", "clickable", "editable", "enabled",
                "visible", "caseSensitive", "index");
        requireUnlocked();
        int index = (int) JsonArgs.optionalLong(params, "index", 0);
        return requireAccessibility().clickSelector(selectorFrom(params), index);
    }

    private JSONObject uiSetText(JSONObject params) throws ApiException {
        JsonArgs.only(params, "text", "textContains", "description", "descriptionContains",
                "viewId", "className", "packageName", "clickable", "editable", "enabled",
                "visible", "caseSensitive", "index", "value");
        requireUnlocked();
        int index = (int) JsonArgs.optionalLong(params, "index", 0);
        String value = JsonArgs.requiredStringAllowEmpty(params, "value", SecurityValidators.MAX_TEXT_LENGTH);
        return requireAccessibility().setTextSelector(selectorFrom(params), index, value);
    }

    private JSONObject uiWaitFor(JSONObject params) throws ApiException {
        JsonArgs.only(params, "text", "textContains", "description", "descriptionContains",
                "viewId", "className", "packageName", "clickable", "editable", "enabled",
                "visible", "caseSensitive", "state", "timeoutMs", "pollMs");
        requireUnlocked();
        String state = JsonArgs.optionalString(params, "state", "present", 16);
        if (!state.equals("present") && !state.equals("absent"))
            throw new ApiException("INVALID_ARGUMENT", "Invalid wait state");
        long timeout = JsonArgs.optionalLong(params, "timeoutMs", 5_000);
        long poll = JsonArgs.optionalLong(params, "pollMs", 250);
        return requireAccessibility().waitFor(selectorFrom(params), state.equals("present"), timeout, poll);
    }

    private JSONObject screenshot(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        requireUnlocked();
        McpAccessibilityService service = requireAccessibility();
        byte[] png = service.screenshot();
        String data = Base64.encodeToString(png, Base64.NO_WRAP);
        if (data.length() > 8 * 1024 * 1024) {
            throw new ApiException("RESPONSE_TOO_LARGE", "Screenshot exceeds response limit");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, options);
        Point display = service.screenSize();
        JSONObject result = new JSONObject();
        try {
            result.put("mimeType", "image/png");
            result.put("data", data);
            result.put("width", Math.max(1, options.outWidth));
            result.put("height", Math.max(1, options.outHeight));
            result.put("displayWidth", display.x);
            result.put("displayHeight", display.y);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode screenshot");
        }
    }

    private JSONObject tap(JSONObject params) throws ApiException {
        JsonArgs.only(params, "x", "y");
        McpAccessibilityService service = requireActionService();
        long x = coordinate(params, "x");
        long y = coordinate(params, "y");
        requireBounds(service, x, y);
        service.tap(x, y);
        return ok();
    }

    private JSONObject doubleTap(JSONObject params) throws ApiException {
        JsonArgs.only(params, "x", "y", "nx", "ny");
        McpAccessibilityService service = requireActionService();
        CoordinateResolver.PointValue point = resolvePoint(params, service, "", "");
        service.doubleTap(point.x, point.y);
        return ok();
    }

    private JSONObject longPress(JSONObject params) throws ApiException {
        JsonArgs.only(params, "x", "y", "durationMs");
        McpAccessibilityService service = requireActionService();
        long x = coordinate(params, "x");
        long y = coordinate(params, "y");
        long duration = JsonArgs.requiredLong(params, "durationMs");
        if (duration < 400 || duration > 3_000) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid duration");
        }
        requireBounds(service, x, y);
        service.longPress(x, y, duration);
        return ok();
    }

    private JSONObject swipe(JSONObject params) throws ApiException {
        JsonArgs.only(params, "x1", "y1", "x2", "y2", "durationMs");
        McpAccessibilityService service = requireActionService();
        long x1 = coordinate(params, "x1");
        long y1 = coordinate(params, "y1");
        long x2 = coordinate(params, "x2");
        long y2 = coordinate(params, "y2");
        long duration = JsonArgs.requiredLong(params, "durationMs");
        if (duration < 100 || duration > 3_000) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid duration");
        }
        requireBounds(service, x1, y1);
        requireBounds(service, x2, y2);
        service.swipe(x1, y1, x2, y2, duration);
        return ok();
    }

    private JSONObject drag(JSONObject params) throws ApiException {
        JsonArgs.only(params, "x1", "y1", "nx1", "ny1", "x2", "y2", "nx2", "ny2", "durationMs");
        McpAccessibilityService service = requireActionService();
        CoordinateResolver.PointValue start = resolvePoint(params, service, "1", "1");
        CoordinateResolver.PointValue end = resolvePoint(params, service, "2", "2");
        long duration = JsonArgs.optionalLong(params, "durationMs", 600);
        service.drag(start.x, start.y, end.x, end.y, duration);
        return ok();
    }

    private JSONObject pinch(JSONObject params) throws ApiException {
        JsonArgs.only(params, "x", "y", "nx", "ny", "direction", "amount", "durationMs");
        McpAccessibilityService service = requireActionService();
        CoordinateResolver.PointValue center = resolvePoint(params, service, "", "");
        String direction = JsonArgs.requiredString(params, "direction", 8);
        double amount = JsonArgs.optionalDouble(params, "amount", 0.5);
        long duration = JsonArgs.optionalLong(params, "durationMs", 500);
        service.pinch(center.x, center.y, direction, amount, duration);
        return ok();
    }

    private JSONObject scroll(JSONObject params) throws ApiException {
        JsonArgs.only(params, "direction");
        String direction = JsonArgs.requiredString(params, "direction", 16);
        Set<String> allowed = new HashSet<>(Arrays.asList("up", "down", "left", "right"));
        if (!allowed.contains(direction)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid scroll direction");
        }
        McpAccessibilityService service = requireActionService();
        service.scrollDirection(direction);
        return ok();
    }

    private JSONObject pressKey(JSONObject params) throws ApiException {
        JsonArgs.only(params, "key");
        requireUnlocked();
        String key = JsonArgs.requiredString(params, "key", 32);
        McpAccessibilityService service = requireAccessibility();
        if ("back".equals(key)) {
            service.globalAction(McpAccessibilityService.GLOBAL_ACTION_BACK);
            return backendOk("accessibility");
        }
        if ("home".equals(key)) {
            service.globalAction(McpAccessibilityService.GLOBAL_ACTION_HOME);
            return backendOk("accessibility");
        }
        int keyCode;
        switch (key) {
            case "enter": keyCode = android.view.KeyEvent.KEYCODE_ENTER; break;
            case "delete": keyCode = android.view.KeyEvent.KEYCODE_DEL; break;
            case "escape": keyCode = android.view.KeyEvent.KEYCODE_ESCAPE; break;
            case "tab": keyCode = android.view.KeyEvent.KEYCODE_TAB; break;
            case "dpad_up": keyCode = android.view.KeyEvent.KEYCODE_DPAD_UP; break;
            case "dpad_down": keyCode = android.view.KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "dpad_left": keyCode = android.view.KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "dpad_right": keyCode = android.view.KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "dpad_center": keyCode = android.view.KeyEvent.KEYCODE_DPAD_CENTER; break;
            case "volume_up": keyCode = android.view.KeyEvent.KEYCODE_VOLUME_UP; break;
            case "volume_down": keyCode = android.view.KeyEvent.KEYCODE_VOLUME_DOWN; break;
            case "volume_mute": keyCode = android.view.KeyEvent.KEYCODE_VOLUME_MUTE; break;
            case "media_play_pause": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "media_next": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "media_previous": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            default: throw new ApiException("INVALID_ARGUMENT", "Unsupported key");
        }
        JSONObject result = ShizukuBridge.execute(context, "input keyevent " + keyCode, "", "", 2_000);
        if (!result.isNull("exitCode") && result.optInt("exitCode", -1) != 0) {
            throw new ApiException("ACTION_REJECTED", "Key event failed");
        }
        return backendOk("shizuku");
    }

    private JSONObject inputText(JSONObject params) throws ApiException {
        JsonArgs.only(params, "text");
        requireUnlocked();
        String text = JsonArgs.requiredStringAllowEmpty(params, "text", SecurityValidators.MAX_TEXT_LENGTH);
        requireAccessibility().setFocusedText(text);
        return ok();
    }

    private JSONObject globalAction(JSONObject params) throws ApiException {
        JsonArgs.only(params, "action");
        String action = JsonArgs.requiredString(params, "action", 32);
        int nativeAction;
        switch (action) {
            case "home": nativeAction = McpAccessibilityService.GLOBAL_ACTION_HOME; break;
            case "back": nativeAction = McpAccessibilityService.GLOBAL_ACTION_BACK; break;
            case "recents": nativeAction = McpAccessibilityService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": nativeAction = McpAccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": nativeAction = McpAccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; break;
            default: throw new ApiException("INVALID_ARGUMENT", "Invalid global action");
        }
        requireUnlocked();
        requireAccessibility().globalAction(nativeAction);
        return ok();
    }

    private JSONObject launchApp(JSONObject params) throws ApiException {
        JsonArgs.only(params, "packageName");
        String packageName = JsonArgs.requiredString(params, "packageName", SecurityValidators.MAX_PACKAGE_LENGTH);
        if (!SecurityValidators.isValidPackageName(packageName)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid package name");
        }
        requireUnlocked();
        requireAccessibility().launch(packageName);
        return ok();
    }

    private JSONObject apps(JSONObject params) throws ApiException {
        JsonArgs.only(params, "query", "limit");
        String query = JsonArgs.optionalStringAllowEmpty(params, "query", "", 100);
        int limit = (int) JsonArgs.optionalLong(params, "limit", 100);
        return AndroidSystemTools.apps(context, query, limit);
    }

    private JSONObject appDetails(JSONObject params) throws ApiException {
        JsonArgs.only(params, "packageName");
        String packageName = JsonArgs.requiredString(params, "packageName", SecurityValidators.MAX_PACKAGE_LENGTH);
        return AndroidSystemTools.appDetails(context, packageName);
    }

    private JSONObject openAppSettings(JSONObject params) throws ApiException {
        JsonArgs.only(params, "packageName");
        requireUnlocked();
        String packageName = JsonArgs.requiredString(params, "packageName", SecurityValidators.MAX_PACKAGE_LENGTH);
        return AndroidSystemTools.openAppSettings(context, packageName);
    }

    private JSONObject clipboardGet(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        requireUnlocked();
        return AndroidSystemTools.clipboardGet(context);
    }

    private JSONObject clipboardSet(JSONObject params) throws ApiException {
        JsonArgs.only(params, "text");
        requireUnlocked();
        return AndroidSystemTools.clipboardSet(context,
                JsonArgs.requiredStringAllowEmpty(params, "text", SecurityValidators.MAX_TEXT_LENGTH));
    }

    private JSONObject deviceInfo(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        return AndroidSystemTools.deviceInfo(context);
    }

    private JSONObject openUri(JSONObject params) throws ApiException {
        JsonArgs.only(params, "uri");
        requireUnlocked();
        return AndroidSystemTools.openUri(context,
                JsonArgs.requiredString(params, "uri", SecurityValidators.MAX_URI_LENGTH));
    }

    private JSONObject shareText(JSONObject params) throws ApiException {
        JsonArgs.only(params, "text", "title");
        requireUnlocked();
        String text = JsonArgs.requiredStringAllowEmpty(params, "text", SecurityValidators.MAX_TEXT_LENGTH);
        String title = JsonArgs.optionalStringAllowEmpty(params, "title", "", 120);
        return AndroidSystemTools.shareText(context, text, title);
    }

    private JSONObject notifications(JSONObject params) throws ApiException {
        JsonArgs.only(params, "limit");
        int limit = (int) JsonArgs.optionalLong(params, "limit", 50);
        return requireNotificationService().list(limit);
    }

    private JSONObject notificationOpen(JSONObject params) throws ApiException {
        JsonArgs.only(params, "key");
        requireUnlocked();
        return requireNotificationService().open(JsonArgs.requiredString(params, "key", 512));
    }

    private JSONObject notificationDismiss(JSONObject params) throws ApiException {
        JsonArgs.only(params, "key");
        return requireNotificationService().dismiss(JsonArgs.requiredString(params, "key", 512));
    }

    private JSONObject notificationReply(JSONObject params) throws ApiException {
        JsonArgs.only(params, "key", "text");
        requireUnlocked();
        return requireNotificationService().reply(
                JsonArgs.requiredString(params, "key", 512),
                JsonArgs.requiredStringAllowEmpty(params, "text", SecurityValidators.MAX_TEXT_LENGTH));
    }

    private JSONObject mediaSessions(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        return AndroidSystemTools.mediaSessions(context);
    }

    private JSONObject mediaAction(JSONObject params) throws ApiException {
        JsonArgs.only(params, "packageName", "action");
        String pkg = JsonArgs.optionalStringAllowEmpty(params, "packageName", "", SecurityValidators.MAX_PACKAGE_LENGTH);
        String action = JsonArgs.requiredString(params, "action", 16);
        return AndroidSystemTools.mediaAction(context, pkg, action);
    }

    private JSONObject volumeGet(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        return AndroidSystemTools.volumes(context);
    }

    private JSONObject volumeSet(JSONObject params) throws ApiException {
        JsonArgs.only(params, "stream", "level");
        String stream = JsonArgs.requiredString(params, "stream", 20);
        int level = (int) JsonArgs.requiredLong(params, "level");
        return AndroidSystemTools.setVolume(context, stream, level);
    }

    private JSONObject events(JSONObject params) throws ApiException {
        JsonArgs.only(params, "afterId", "limit");
        long after = JsonArgs.optionalLong(params, "afterId", 0);
        int limit = (int) JsonArgs.optionalLong(params, "limit", 100);
        return EventJournal.since(after, limit);
    }

    private JSONObject eventsWait(JSONObject params) throws ApiException {
        JsonArgs.only(params, "afterId", "limit", "timeoutMs");
        long after = JsonArgs.optionalLong(params, "afterId", 0);
        int limit = (int) JsonArgs.optionalLong(params, "limit", 100);
        long timeout = JsonArgs.optionalLong(params, "timeoutMs", 8_000);
        return EventJournal.waitSince(after, limit, timeout);
    }

    private JSONObject shellStatus(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        return TermuxBridge.status(context);
    }

    private JSONObject shell(JSONObject params) throws ApiException {
        JsonArgs.only(params, "script", "stdin", "workdir", "timeoutMs");
        String script = JsonArgs.requiredString(params, "script", SecurityValidators.MAX_SHELL_INPUT);
        String stdin = JsonArgs.optionalStringAllowEmpty(params, "stdin", "", SecurityValidators.MAX_SHELL_INPUT);
        String workdir = JsonArgs.optionalStringAllowEmpty(params, "workdir", "", 1024);
        long timeout = JsonArgs.optionalLong(params, "timeoutMs", 8_000);
        return TermuxBridge.execute(context, script, stdin, workdir, timeout);
    }

    private JSONObject shizukuStatus(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        return ShizukuBridge.status(context);
    }

    private JSONObject shizukuShell(JSONObject params) throws ApiException {
        JsonArgs.only(params, "script", "stdin", "workdir", "timeoutMs");
        String script = JsonArgs.requiredString(params, "script", SecurityValidators.MAX_SHELL_INPUT);
        String stdin = JsonArgs.optionalStringAllowEmpty(params, "stdin", "", SecurityValidators.MAX_SHELL_INPUT);
        String workdir = JsonArgs.optionalStringAllowEmpty(params, "workdir", "", 1024);
        long timeout = JsonArgs.optionalLong(params, "timeoutMs", 8_000);
        return ShizukuBridge.execute(context, script, stdin, workdir, timeout);
    }

    private JSONObject privilegedStatus(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        return AndroidSystemTools.privilegedStatus(context);
    }

    private JSONObject capabilities(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        return CapabilityRouter.status(context, roots);
    }

    private JSONObject forceStopApp(JSONObject params) throws ApiException {
        JsonArgs.only(params, "packageName");
        String packageName = JsonArgs.requiredString(
                params, "packageName", SecurityValidators.MAX_PACKAGE_LENGTH);
        return CapabilityRouter.forceStop(context, packageName);
    }

    private JSONObject logcat(JSONObject params) throws ApiException {
        JsonArgs.only(params, "packageName", "tag", "level", "lines", "sinceSeconds");
        String packageName = JsonArgs.optionalStringAllowEmpty(
                params, "packageName", "", SecurityValidators.MAX_PACKAGE_LENGTH);
        String tag = JsonArgs.optionalStringAllowEmpty(params, "tag", "", 80);
        String level = JsonArgs.optionalString(params, "level", "I", 1);
        int lines = (int) JsonArgs.optionalLong(params, "lines", 200);
        int sinceSeconds = (int) JsonArgs.optionalLong(params, "sinceSeconds", 300);
        return CapabilityRouter.logcat(context, packageName, tag, level, lines, sinceSeconds);
    }

    private JSONObject fileRoots(JSONObject params) throws ApiException {
        JsonArgs.only(params);
        JSONArray result = new JSONArray();
        for (FileRootStore.Root root : roots.list()) {
            try {
                JSONObject entry = files.stat(root.id, "");
                entry.put("rootId", root.id);
                result.put(entry);
            } catch (ApiException ignored) {
                // Revoked or unavailable providers are not advertised as usable roots.
            } catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to encode roots"); }
        }
        JSONObject response = new JSONObject();
        try {
            response.put("roots", result);
            return response;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode roots");
        }
    }

    private JSONObject fileList(JSONObject params) throws ApiException {
        JsonArgs.only(params, "rootId", "path", "offset", "limit");
        String rootId = JsonArgs.requiredString(params, "rootId", 80);
        if (!SecurityValidators.isValidRootId(rootId)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid root");
        }
        String path = params.has("path")
                ? JsonArgs.requiredStringAllowEmpty(params, "path", SecurityValidators.MAX_PATH_LENGTH) : "";
        long offset = JsonArgs.optionalLong(params, "offset", 0);
        long limit = JsonArgs.optionalLong(params, "limit", 100);
        if (limit < 1 || limit > 200) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid limit");
        }
        return files.list(rootId, path, offset, (int) limit);
    }

    private JSONObject fileStat(JSONObject params) throws ApiException {
        JsonArgs.only(params, "rootId", "path");
        String rootId = JsonArgs.requiredString(params, "rootId", 80);
        String path = params.has("path")
                ? JsonArgs.requiredStringAllowEmpty(params, "path", SecurityValidators.MAX_PATH_LENGTH) : "";
        return files.stat(rootId, path);
    }

    private JSONObject fileRead(JSONObject params) throws ApiException {
        JsonArgs.only(params, "rootId", "path", "offset", "length");
        String rootId = JsonArgs.requiredString(params, "rootId", 80);
        String path = params.has("path")
                ? JsonArgs.requiredStringAllowEmpty(params, "path", SecurityValidators.MAX_PATH_LENGTH) : "";
        long offset = JsonArgs.optionalLong(params, "offset", 0);
        long length = JsonArgs.optionalLong(params, "length", 65_536);
        return files.read(rootId, path, offset, length);
    }

    private JSONObject fileSearch(JSONObject params) throws ApiException {
        JsonArgs.only(params, "rootId", "path", "query", "maxDepth", "limit");
        String rootId = JsonArgs.requiredString(params, "rootId", 80);
        String path = JsonArgs.optionalStringAllowEmpty(params, "path", "", SecurityValidators.MAX_PATH_LENGTH);
        String query = JsonArgs.optionalStringAllowEmpty(params, "query", "", 255);
        int maxDepth = (int) JsonArgs.optionalLong(params, "maxDepth", 8);
        int limit = (int) JsonArgs.optionalLong(params, "limit", 100);
        return files.search(rootId, path, query, maxDepth, limit);
    }

    private JSONObject fileWrite(JSONObject params) throws ApiException {
        JsonArgs.only(params, "rootId", "path", "data", "offset", "truncate", "mimeType");
        String rootId = JsonArgs.requiredString(params, "rootId", 80);
        String path = JsonArgs.requiredString(params, "path", SecurityValidators.MAX_PATH_LENGTH);
        String data = JsonArgs.requiredStringAllowEmpty(params, "data", 400_000);
        long offset = JsonArgs.optionalLong(params, "offset", 0);
        boolean truncate = JsonArgs.optionalBoolean(params, "truncate", offset == 0);
        String mime = JsonArgs.optionalString(params, "mimeType", "application/octet-stream", 200);
        return files.write(rootId, path, data, offset, truncate, mime);
    }

    private JSONObject fileMkdir(JSONObject params) throws ApiException {
        JsonArgs.only(params, "rootId", "path");
        return files.mkdir(JsonArgs.requiredString(params, "rootId", 80),
                JsonArgs.requiredString(params, "path", SecurityValidators.MAX_PATH_LENGTH));
    }

    private JSONObject fileRename(JSONObject params) throws ApiException {
        JsonArgs.only(params, "rootId", "path", "newName");
        return files.rename(JsonArgs.requiredString(params, "rootId", 80),
                JsonArgs.requiredString(params, "path", SecurityValidators.MAX_PATH_LENGTH),
                JsonArgs.requiredString(params, "newName", 255));
    }

    private JSONObject fileMove(JSONObject params) throws ApiException {
        JsonArgs.only(params, "rootId", "path", "targetDirectory");
        return files.move(JsonArgs.requiredString(params, "rootId", 80),
                JsonArgs.requiredString(params, "path", SecurityValidators.MAX_PATH_LENGTH),
                JsonArgs.optionalStringAllowEmpty(params, "targetDirectory", "", SecurityValidators.MAX_PATH_LENGTH));
    }

    private JSONObject fileCopy(JSONObject params) throws ApiException {
        JsonArgs.only(params, "rootId", "path", "targetDirectory");
        return files.copy(JsonArgs.requiredString(params, "rootId", 80),
                JsonArgs.requiredString(params, "path", SecurityValidators.MAX_PATH_LENGTH),
                JsonArgs.optionalStringAllowEmpty(params, "targetDirectory", "", SecurityValidators.MAX_PATH_LENGTH));
    }

    private JSONObject fileDelete(JSONObject params) throws ApiException {
        JsonArgs.only(params, "rootId", "path");
        return files.delete(JsonArgs.requiredString(params, "rootId", 80),
                JsonArgs.requiredString(params, "path", SecurityValidators.MAX_PATH_LENGTH));
    }

    private McpAccessibilityService requireActionService() throws ApiException {
        requireUnlocked();
        return requireAccessibility();
    }

    private McpAccessibilityService requireAccessibility() throws ApiException {
        McpAccessibilityService service = McpAccessibilityService.active();
        if (service == null) {
            throw new ApiException("ACCESSIBILITY_DISABLED", "Accessibility service is disabled");
        }
        return service;
    }

    private McpNotificationService requireNotificationService() throws ApiException {
        McpNotificationService service = McpNotificationService.active();
        if (service == null) {
            throw new ApiException("NOTIFICATION_ACCESS_DISABLED", "Notification access is disabled");
        }
        return service;
    }

    private static JSONObject selectorFrom(JSONObject params) throws ApiException {
        JSONObject selector = new JSONObject();
        String[] keys = {"text", "textContains", "description", "descriptionContains",
                "viewId", "className", "packageName", "clickable", "editable", "enabled",
                "visible", "caseSensitive"};
        try {
            for (String key : keys) if (params.has(key)) selector.put(key, params.get(key));
            return selector;
        } catch (JSONException e) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid selector");
        }
    }

    private void requireUnlocked() throws ApiException {
        if (isLocked()) {
            throw new ApiException("LOCKED_UI", "Action unavailable while device is locked");
        }
    }

    private boolean isLocked() {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && (keyguard.isKeyguardLocked() || keyguard.isDeviceLocked());
    }

    private static long coordinate(JSONObject params, String key) throws ApiException {
        long value = JsonArgs.requiredLong(params, key);
        if (value < 0 || value > 16_384) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid coordinate");
        }
        return value;
    }

    private static CoordinateResolver.PointValue resolvePoint(
            JSONObject params, McpAccessibilityService service, String absoluteSuffix, String normalizedSuffix)
            throws ApiException {
        String xKey = "x" + absoluteSuffix;
        String yKey = "y" + absoluteSuffix;
        String nxKey = "nx" + normalizedSuffix;
        String nyKey = "ny" + normalizedSuffix;
        Long x = params.has(xKey) ? JsonArgs.requiredLong(params, xKey) : null;
        Long y = params.has(yKey) ? JsonArgs.requiredLong(params, yKey) : null;
        Integer nx = params.has(nxKey) ? Math.toIntExact(JsonArgs.requiredLong(params, nxKey)) : null;
        Integer ny = params.has(nyKey) ? Math.toIntExact(JsonArgs.requiredLong(params, nyKey)) : null;
        Point size = service.screenSize();
        try {
            return CoordinateResolver.resolve(size.x, size.y, x, y, nx, ny);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid coordinate mode");
        }
    }

    private static void requireBounds(McpAccessibilityService service, long x, long y) throws ApiException {
        Point size = service.screenSize();
        if (x >= size.x || y >= size.y) {
            throw new ApiException("INVALID_ARGUMENT", "Coordinate outside display");
        }
    }

    private Point displaySize() {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        if (manager != null) {
            manager.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics = context.getResources().getDisplayMetrics();
        }
        return new Point(Math.max(1, metrics.widthPixels), Math.max(1, metrics.heightPixels));
    }

    private static JSONObject ok() throws ApiException {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode result");
        }
    }

    private static JSONObject backendOk(String backend) throws ApiException {
        JSONObject result = ok();
        try {
            result.put("backend", backend);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode action result");
        }
    }
}
