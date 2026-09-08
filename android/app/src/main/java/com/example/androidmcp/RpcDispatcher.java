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

    private Object dispatchAllowed(String method, JSONObject params) throws ApiException {
        switch (method) {
            case "status": return status(params);
            case "ui_tree": return uiTree(params);
            case "ui_find": return uiFind(params);
            case "ui_click": return uiClick(params);
            case "ui_set_text": return uiSetText(params);
            case "ui_wait_for": return uiWaitFor(params);
            case "screenshot": return screenshot(params);
            case "tap": return tap(params);
            case "long_press": return longPress(params);
            case "swipe": return swipe(params);
            case "scroll": return scroll(params);
            case "input_text": return inputText(params);
            case "global_action": return globalAction(params);
            case "launch_app": return launchApp(params);
            case "apps": return apps(params);
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

    private JSONObject scroll(JSONObject params) throws ApiException {
        JsonArgs.only(params, "direction");
        String direction = JsonArgs.requiredString(params, "direction", 16);
        Set<String> allowed = new HashSet<>(Arrays.asList("up", "down", "left", "right"));
        if (!allowed.contains(direction)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid scroll direction");
        }
        McpAccessibilityService service = requireActionService();
        Point size = service.screenSize();
        long centerX = size.x / 2L;
        long centerY = size.y / 2L;
        long x1 = centerX;
        long y1 = centerY;
        long x2 = centerX;
        long y2 = centerY;
        if ("down".equals(direction)) { y1 = (size.y * 3L) / 4L; y2 = size.y / 4L; }
        if ("up".equals(direction)) { y1 = size.y / 4L; y2 = (size.y * 3L) / 4L; }
        if ("right".equals(direction)) { x1 = (size.x * 3L) / 4L; x2 = size.x / 4L; }
        if ("left".equals(direction)) { x1 = size.x / 4L; x2 = (size.x * 3L) / 4L; }
        service.swipe(x1, y1, x2, y2, 400);
        return ok();
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
}
