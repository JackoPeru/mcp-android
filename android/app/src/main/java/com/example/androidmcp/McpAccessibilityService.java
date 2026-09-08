package com.example.androidmcp;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Accessibility bridge. It exposes only the active user UI and never logs node data. */
public final class McpAccessibilityService extends AccessibilityService {
    private static final int MAX_NODES = 500;
    private static final int MAX_NODE_TEXT = 512;
    private static final long OP_TIMEOUT_MS = 4_000;
    private static final AtomicReference<McpAccessibilityService> ACTIVE = new AtomicReference<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean nativeBusy = new java.util.concurrent.atomic.AtomicBoolean();

    public boolean operationInFlight() { return nativeBusy.get(); }

    private void beginNative(RequestScope scope) throws ApiException {
        if (scope != null) scope.beginAction(nativeBusy);
        else if (!nativeBusy.compareAndSet(false, true)) throw new ApiException("BUSY", "Native operation in flight");
    }

    public static McpAccessibilityService active() {
        return ACTIVE.get();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.eventTypes = android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        ACTIVE.set(this);
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // Do not persist event text. Only metadata is kept in a bounded in-memory journal.
        if (event != null) {
            EventJournal.add("ui", String.valueOf(event.getPackageName()),
                    android.view.accessibility.AccessibilityEvent.eventTypeToString(event.getEventType()));
        }
    }

    @Override
    public void onInterrupt() {
        McpForegroundService.stopNow();
    }

    @Override
    public void onDestroy() {
        ACTIVE.compareAndSet(this, null);
        super.onDestroy();
    }

    public Point screenSize() {
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        if (manager != null) {
            manager.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics.widthPixels = getResources().getDisplayMetrics().widthPixels;
            metrics.heightPixels = getResources().getDisplayMetrics().heightPixels;
        }
        return new Point(Math.max(1, metrics.widthPixels), Math.max(1, metrics.heightPixels));
    }

    public JSONObject uiTree() throws ApiException {
        return onMain(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                throw new ApiException("UI_UNAVAILABLE", "No active accessibility window");
            }
            Counter counter = new Counter();
            JSONObject result = new JSONObject();
            JSONObject encoded;
            try { encoded = encodeNode(root, counter, 0); } finally { root.recycle(); }
            try {
                result.put("root", encoded == null ? JSONObject.NULL : encoded);
                result.put("truncated", counter.truncated);
                result.put("nodeCount", counter.count);
            } catch (JSONException e) {
                throw new ApiException("INTERNAL", "Unable to encode UI tree");
            }
            return result;
        });
    }

    public boolean tap(long x, long y) throws ApiException {
        return gesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path(x, y, x, y), 0, 80))
                .build());
    }

    public boolean longPress(long x, long y, long durationMs) throws ApiException {
        return gesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path(x, y, x, y), 0, durationMs))
                .build());
    }

    public boolean doubleTap(long x, long y) throws ApiException {
        GestureDescription description = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path(x, y, x, y), 0, 70))
                .addStroke(new GestureDescription.StrokeDescription(path(x, y, x, y), 150, 70))
                .build();
        return gesture(description);
    }

    public boolean swipe(long x1, long y1, long x2, long y2, long durationMs) throws ApiException {
        return gesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path(x1, y1, x2, y2), 0, durationMs))
                .build());
    }

    public boolean drag(long x1, long y1, long x2, long y2, long durationMs) throws ApiException {
        if (durationMs < 150 || durationMs > 3_000) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid drag duration");
        }
        return swipe(x1, y1, x2, y2, durationMs);
    }

    public boolean pinch(long centerX, long centerY, String direction, double amount, long durationMs)
            throws ApiException {
        if ((!"in".equals(direction) && !"out".equals(direction))
                || amount < 0.1 || amount > 0.8 || durationMs < 150 || durationMs > 2_000) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid pinch");
        }
        Point size = screenSize();
        if (centerX < 0 || centerY < 0 || centerX >= size.x || centerY >= size.y) {
            throw new ApiException("INVALID_ARGUMENT", "Pinch center outside display");
        }
        long horizontalRoom = Math.min(centerX, size.x - 1L - centerX);
        long far = Math.min(Math.max(20, Math.min(size.x, size.y) / 5L), horizontalRoom);
        if (far < 20) throw new ApiException("INVALID_ARGUMENT", "Pinch center too close to edge");
        long near = Math.max(8, Math.round(far * (1.0 - amount)));
        long leftStart = "in".equals(direction) ? centerX - far : centerX - near;
        long leftEnd = "in".equals(direction) ? centerX - near : centerX - far;
        long rightStart = "in".equals(direction) ? centerX + far : centerX + near;
        long rightEnd = "in".equals(direction) ? centerX + near : centerX + far;
        GestureDescription description = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(
                        path(leftStart, centerY, leftEnd, centerY), 0, durationMs))
                .addStroke(new GestureDescription.StrokeDescription(
                        path(rightStart, centerY, rightEnd, centerY), 0, durationMs))
                .build();
        return gesture(description);
    }

    public boolean scrollDirection(String direction) throws ApiException {
        Point size = screenSize();
        long centerX = size.x / 2L;
        long centerY = size.y / 2L;
        long x1 = centerX;
        long y1 = centerY;
        long x2 = centerX;
        long y2 = centerY;
        switch (direction) {
            case "down": y1 = (size.y * 3L) / 4L; y2 = size.y / 4L; break;
            case "up": y1 = size.y / 4L; y2 = (size.y * 3L) / 4L; break;
            case "right": x1 = (size.x * 3L) / 4L; x2 = size.x / 4L; break;
            case "left": x1 = size.x / 4L; x2 = (size.x * 3L) / 4L; break;
            default: throw new ApiException("INVALID_ARGUMENT", "Invalid scroll direction");
        }
        return swipe(x1, y1, x2, y2, 400);
    }

    public boolean setFocusedText(String text) throws ApiException {
        return onMain(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo focused = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            try {
                if (focused == null || !focused.isEditable() || getPackageName().equals(String.valueOf(focused.getPackageName())))
                    throw new ApiException("NO_EDITABLE_FOCUS", "No editable field is focused");
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
                    throw new ApiException("INPUT_REJECTED", "Focused field rejected text");
                return true;
            } finally {
                if (focused != null) focused.recycle();
                if (root != null) root.recycle();
            }
        });
    }

    /** Compact flattened context intended for agent loops and semantic snapshotting. */
    public JSONObject compactContext(boolean includeInvisible, int maxNodes) throws ApiException {
        if (maxNodes < 1 || maxNodes > MAX_NODES) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid compact node limit");
        }
        return onMain(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) throw new ApiException("UI_UNAVAILABLE", "No active accessibility window");
            CompactCounter counter = new CompactCounter(maxNodes);
            JSONArray nodes = new JSONArray();
            AccessibilityNodeInfo focused = null;
            try {
                collectCompact(root, "0", includeInvisible, nodes, counter);
                focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                Point size = screenSize();
                WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
                int rotation = manager == null ? 0 : manager.getDefaultDisplay().getRotation();
                boolean keyboardVisible = false;
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo window : windows) {
                        if (window != null && window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                            keyboardVisible = true;
                            break;
                        }
                    }
                }
                JSONObject display = new JSONObject()
                        .put("width", size.x)
                        .put("height", size.y)
                        .put("rotation", rotation)
                        .put("orientation", size.y >= size.x ? "portrait" : "landscape");
                JSONObject input = new JSONObject()
                        .put("keyboardVisible", keyboardVisible)
                        .put("focusedEditable", focused != null && focused.isEditable());
                return new JSONObject()
                        .put("packageName", capped(root.getPackageName()))
                        .put("windowClass", capped(root.getClassName()))
                        .put("windowId", root.getWindowId())
                        .put("display", display)
                        .put("input", input)
                        .put("nodes", nodes)
                        .put("nodeCount", counter.count)
                        .put("truncated", counter.truncated)
                        .put("webViewDetected", counter.webViewDetected);
            } catch (JSONException e) {
                throw new ApiException("INTERNAL", "Unable to encode compact UI context");
            } finally {
                if (focused != null) focused.recycle();
                root.recycle();
            }
        });
    }

    public JSONObject find(JSONObject selectorJson, int limit) throws ApiException {
        UiSelector selector = UiSelector.from(selectorJson);
        if (limit < 1 || limit > 100) throw new ApiException("INVALID_ARGUMENT", "Invalid match limit");
        return onMain(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) throw new ApiException("UI_UNAVAILABLE", "No active accessibility window");
            JSONArray matches = new JSONArray();
            MatchCounter counter = new MatchCounter(limit);
            try { collectMatches(root, selector, "0", matches, counter); }
            finally { root.recycle(); }
            JSONObject result = new JSONObject();
            try {
                result.put("matches", matches);
                result.put("count", matches.length());
                result.put("truncated", counter.truncated);
                return result;
            } catch (JSONException e) {
                throw new ApiException("INTERNAL", "Unable to encode UI matches");
            }
        });
    }

    public JSONObject clickSelector(JSONObject selectorJson, int index) throws ApiException {
        UiSelector selector = UiSelector.from(selectorJson);
        if (index < 0 || index > 99) throw new ApiException("INVALID_ARGUMENT", "Invalid match index");
        return onMain(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) throw new ApiException("UI_UNAVAILABLE", "No active accessibility window");
            ActionResult result = new ActionResult(index);
            try { clickMatch(root, selector, result); }
            finally { root.recycle(); }
            if (!result.found) throw new ApiException("NOT_FOUND", "Matching UI element not found");
            if (!result.performed) throw new ApiException("ACTION_REJECTED", "Matching element is not clickable");
            return okJson("matchedIndex", index);
        });
    }

    public JSONObject setTextSelector(JSONObject selectorJson, int index, String text) throws ApiException {
        UiSelector selector = UiSelector.from(selectorJson);
        if (index < 0 || index > 99) throw new ApiException("INVALID_ARGUMENT", "Invalid match index");
        return onMain(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) throw new ApiException("UI_UNAVAILABLE", "No active accessibility window");
            TextResult result = new TextResult(index, text);
            try { setTextMatch(root, selector, result); }
            finally { root.recycle(); }
            if (!result.found) throw new ApiException("NOT_FOUND", "Matching UI element not found");
            if (!result.performed) throw new ApiException("INPUT_REJECTED", "Matching element rejected text");
            return okJson("matchedIndex", index);
        });
    }

    public JSONObject waitFor(JSONObject selectorJson, boolean present, long timeoutMs, long pollMs)
            throws ApiException {
        UiSelector.from(selectorJson); // validate before starting the wait
        if (timeoutMs < 0 || timeoutMs > 12_000 || pollMs < 50 || pollMs > 1_000) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid wait timing");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (;;) {
            JSONObject found = find(selectorJson, 1);
            boolean exists = found.optInt("count", 0) > 0;
            if (exists == present) {
                JSONObject result = new JSONObject();
                try {
                    result.put("matched", exists);
                    result.put("condition", present ? "present" : "absent");
                    return result;
                } catch (JSONException e) {
                    throw new ApiException("INTERNAL", "Unable to encode wait result");
                }
            }
            RequestScope.checkCurrent();
            if (System.nanoTime() >= deadline) throw new ApiException("WAIT_TIMEOUT", "UI condition not reached");
            try { Thread.sleep(Math.min(pollMs, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException("TIMEOUT", "UI wait interrupted");
            }
        }
    }

    public boolean globalAction(int action) throws ApiException {
        return onMain(() -> {
            if (!performGlobalAction(action)) {
                throw new ApiException("ACTION_REJECTED", "Global action rejected");
            }
            return true;
        });
    }

    public void launch(String packageName) throws ApiException {
        onMain(() -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) throw new ApiException("NOT_FOUND", "Application has no launcher activity");
            try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (RuntimeException e) { throw new ApiException("ACTION_REJECTED", "Application launch rejected"); }
            return true;
        });
    }

    public byte[] screenshot() throws ApiException {
        java.util.concurrent.CompletableFuture<ScreenshotResult> future = new java.util.concurrent.CompletableFuture<>();
        RequestScope scope = RequestScope.CURRENT.get();
        Runnable start = () -> {
            if (future.isDone()) return;
            try {
                checkUi(scope);
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult result) {
                        if (!future.complete(result)) result.getHardwareBuffer().close();
                    }
                    @Override public void onFailure(int errorCode) {
                        future.completeExceptionally(new ApiException("SCREENSHOT_UNAVAILABLE", "Android denied screenshot"));
                    }
                });
            } catch (Exception e) { future.completeExceptionally(e); }
        };
        main.post(start);
        ScreenshotResult result;
        try { result = future.get(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (Exception e) {
            future.cancel(false);
            ScreenshotResult late = future.isCancelled() || future.isCompletedExceptionally() ? null : future.getNow(null);
            if (late != null) late.getHardwareBuffer().close();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiException("SCREENSHOT_UNAVAILABLE", "Screenshot unavailable or timed out");
        } finally { main.removeCallbacks(start); }
        Bitmap hardware = null;
        Bitmap software = null;
        try (HardwareBuffer buffer = result.getHardwareBuffer()) {
            if (scope != null) scope.check();
            hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (hardware == null) throw new ApiException("SCREENSHOT_UNAVAILABLE", "Screenshot unavailable");
            software = hardware.copy(Bitmap.Config.ARGB_8888, false);
            ByteArrayOutputStream output = new ByteArrayOutputStream() {
                @Override public synchronized void write(byte[] bytes, int offset, int length) {
                    if (count + length > 6 * 1024 * 1024) throw new IllegalStateException("Image too large");
                    super.write(bytes, offset, length);
                }
                @Override public synchronized void write(int value) {
                    if (count >= 6 * 1024 * 1024) throw new IllegalStateException("Image too large");
                    super.write(value);
                }
            };
            if (software == null || !software.compress(Bitmap.CompressFormat.PNG, 100, output))
                throw new ApiException("SCREENSHOT_UNAVAILABLE", "Screenshot encoding failed");
            return output.toByteArray();
        } catch (IllegalStateException e) { throw new ApiException("RESPONSE_TOO_LARGE", "Screenshot too large"); }
        finally {
            if (software != null) software.recycle();
            if (hardware != null) hardware.recycle();
        }
    }

    private boolean gesture(GestureDescription description) throws ApiException {
        if (nativeBusy.get()) throw new ApiException("BUSY", "Native operation in flight");
        java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<>();
        RequestScope scope = RequestScope.CURRENT.get();
        Runnable start = () -> {
            if (future.isDone()) return;
            boolean started = false;
            try {
                checkUi(scope);
                beginNative(scope);
                started = true;
                if (!dispatchGesture(description, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gesture) { nativeBusy.set(false); future.complete(true); }
                    @Override public void onCancelled(GestureDescription gesture) { nativeBusy.set(false); future.complete(false); }
                }, main)) { nativeBusy.set(false); future.complete(false); }
            } catch (Exception e) {
                if (started) nativeBusy.set(false);
                future.completeExceptionally(e);
            }
        };
        main.post(start);
        try {
            if (!future.get(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new ApiException("ACTION_CANCELLED", "Gesture rejected or cancelled");
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new ApiException("TIMEOUT", "Gesture timed out; outcome unknown");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new ApiException("ACTION_REJECTED", "Gesture rejected");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new ApiException("TIMEOUT", "Gesture interrupted");
        } finally { future.cancel(false); main.removeCallbacks(start); }
    }

    private void checkUi(RequestScope scope) throws ApiException {
        if (scope != null) scope.check();
        android.app.KeyguardManager keyguard = getSystemService(android.app.KeyguardManager.class);
        if (keyguard == null || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked())
            throw new ApiException("LOCKED_UI", "Device locked");
        if (ACTIVE.get() != this || !McpForegroundService.isRunning())
            throw new ApiException("SERVICE_STOPPED", "Remote service stopped");
    }

    private Path path(long x1, long y1, long x2, long y2) {
        Path path = new Path();
        path.moveTo((float) x1, (float) y1);
        path.lineTo((float) x2, (float) y2);
        return path;
    }

    private JSONObject encodeNode(AccessibilityNodeInfo node, Counter counter, int depth) throws ApiException {
        RequestScope.checkCurrent();
        if (node == null || depth > 64 || counter.count >= MAX_NODES) {
            counter.truncated = true;
            return null;
        }
        String packageName = String.valueOf(node.getPackageName());
        if (getPackageName().equals(packageName)) {
            return null;
        }
        counter.count++;
        boolean password = node.isPassword();
        JSONObject object = new JSONObject();
        try {
            object.put("className", capped(node.getClassName()));
            object.put("packageName", packageName);
            object.put("viewId", capped(node.getViewIdResourceName()));
            object.put("bounds", bounds(node));
            object.put("clickable", node.isClickable());
            object.put("enabled", node.isEnabled());
            object.put("visibleToUser", node.isVisibleToUser());
            object.put("editable", node.isEditable());
            object.put("focused", node.isFocused());
            object.put("focusable", node.isFocusable());
            object.put("scrollable", node.isScrollable());
            object.put("password", password);
            if (password) {
                object.put("text", "[REDACTED]");
            } else {
                object.put("text", capped(node.getText()));
                object.put("contentDescription", capped(node.getContentDescription()));
                object.put("hintText", capped(node.getHintText()));
            }
            JSONArray children = new JSONArray();
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo childNode = node.getChild(i);
                JSONObject child;
                try { child = encodeNode(childNode, counter, depth + 1); }
                finally { if (childNode != null) childNode.recycle(); }
                if (child != null) {
                    children.put(child);
                }
                if (counter.truncated) {
                    break;
                }
            }
            object.put("children", children);
            return object;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode UI tree");
        }
    }

    private void collectMatches(AccessibilityNodeInfo node, UiSelector selector, String path,
                                JSONArray matches, MatchCounter counter) throws ApiException {
        RequestScope.checkCurrent();
        if (node == null || counter.seen >= MAX_NODES || matches.length() >= counter.limit) {
            if (node != null) counter.truncated = true;
            return;
        }
        counter.seen++;
        if (!getPackageName().equals(String.valueOf(node.getPackageName())) && selector.matches(node)) {
            matches.put(encodeMatch(node, path));
            if (matches.length() >= counter.limit) {
                counter.truncated = true;
                return;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try { collectMatches(child, selector, path + "/" + i, matches, counter); }
            finally { if (child != null) child.recycle(); }
            if (counter.truncated && matches.length() >= counter.limit) break;
        }
    }

    private void collectCompact(AccessibilityNodeInfo node, String path, boolean includeInvisible,
                                JSONArray nodes, CompactCounter counter) throws ApiException {
        RequestScope.checkCurrent();
        if (node == null || counter.count >= counter.limit) {
            if (node != null) counter.truncated = true;
            return;
        }
        String packageName = String.valueOf(node.getPackageName());
        boolean own = getPackageName().equals(packageName);
        boolean include = !own && (includeInvisible || node.isVisibleToUser());
        if (include) {
            String className = capped(node.getClassName());
            if (className.toLowerCase(Locale.ROOT).contains("webview")) counter.webViewDetected = true;
            nodes.put(encodeCompactNode(node, path));
            counter.count++;
            if (counter.count >= counter.limit && node.getChildCount() > 0) counter.truncated = true;
        }
        if (counter.count >= counter.limit) return;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try { collectCompact(child, path + "/" + i, includeInvisible, nodes, counter); }
            finally { if (child != null) child.recycle(); }
            if (counter.count >= counter.limit) {
                if (i + 1 < node.getChildCount()) counter.truncated = true;
                break;
            }
        }
    }

    private JSONObject encodeCompactNode(AccessibilityNodeInfo node, String path) throws ApiException {
        boolean password = node.isPassword();
        JSONObject object = new JSONObject();
        try {
            object.put("path", path);
            object.put("className", capped(node.getClassName()));
            object.put("packageName", capped(node.getPackageName()));
            object.put("viewId", capped(node.getViewIdResourceName()));
            object.put("bounds", bounds(node));
            object.put("clickable", node.isClickable());
            object.put("editable", node.isEditable());
            object.put("scrollable", node.isScrollable());
            object.put("focused", node.isFocused());
            object.put("enabled", node.isEnabled());
            object.put("visible", node.isVisibleToUser());
            object.put("selected", node.isSelected());
            object.put("checkable", node.isCheckable());
            object.put("checked", node.isChecked());
            object.put("password", password);
            object.put("text", password ? "[REDACTED]" : capped(node.getText()));
            object.put("contentDescription", password ? "" : capped(node.getContentDescription()));
            object.put("hintText", password ? "" : capped(node.getHintText()));
            return object;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode compact UI node");
        }
    }

    private boolean clickMatch(AccessibilityNodeInfo node, UiSelector selector, ActionResult result)
            throws ApiException {
        RequestScope.checkCurrent();
        if (node == null || result.scanned++ >= MAX_NODES) return false;
        if (!getPackageName().equals(String.valueOf(node.getPackageName())) && selector.matches(node)) {
            if (result.current++ == result.target) {
                result.found = true;
                result.performed = performClick(node);
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try {
                if (clickMatch(child, selector, result)) return true;
            } finally { if (child != null) child.recycle(); }
        }
        return false;
    }

    private boolean setTextMatch(AccessibilityNodeInfo node, UiSelector selector, TextResult result)
            throws ApiException {
        RequestScope.checkCurrent();
        if (node == null || result.scanned++ >= MAX_NODES) return false;
        if (!getPackageName().equals(String.valueOf(node.getPackageName())) && selector.matches(node)) {
            if (result.current++ == result.target) {
                result.found = true;
                if (!node.isEditable()) return true;
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result.text);
                result.performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try {
                if (setTextMatch(child, selector, result)) return true;
            } finally { if (child != null) child.recycle(); }
        }
        return false;
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        int depth = 0;
        try {
            while (current != null && depth++ < 8) {
                if (current.isEnabled() && current.isClickable()
                        && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
            return false;
        } finally {
            if (current != null) current.recycle();
        }
    }

    private JSONObject encodeMatch(AccessibilityNodeInfo node, String path) throws ApiException {
        JSONObject object = new JSONObject();
        boolean password = node.isPassword();
        try {
            object.put("path", path);
            object.put("className", capped(node.getClassName()));
            object.put("packageName", capped(node.getPackageName()));
            object.put("viewId", capped(node.getViewIdResourceName()));
            object.put("bounds", bounds(node));
            object.put("clickable", node.isClickable());
            object.put("enabled", node.isEnabled());
            object.put("visibleToUser", node.isVisibleToUser());
            object.put("editable", node.isEditable());
            object.put("focused", node.isFocused());
            object.put("scrollable", node.isScrollable());
            object.put("password", password);
            object.put("text", password ? "[REDACTED]" : capped(node.getText()));
            object.put("contentDescription", password ? "" : capped(node.getContentDescription()));
            object.put("hintText", password ? "" : capped(node.getHintText()));
            return object;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode UI match");
        }
    }

    private static JSONObject okJson(String key, int value) throws ApiException {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put(key, value);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode result");
        }
    }

    private JSONObject bounds(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        JSONObject result = new JSONObject();
        try {
            result.put("left", rect.left);
            result.put("top", rect.top);
            result.put("right", rect.right);
            result.put("bottom", rect.bottom);
        } catch (JSONException ignored) {
            // JSONObject.put of primitive values does not fail in Android's implementation.
        }
        return result;
    }

    private <T> T onMain(ThrowingCallable<T> callable) throws ApiException {
        if (nativeBusy.get()) throw new ApiException("BUSY", "Native operation in flight");
        RequestScope scope = RequestScope.CURRENT.get();
        AtomicReference<ApiException> apiError = new AtomicReference<>();
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(() -> {
            boolean started = false;
            try {
                RequestScope.CURRENT.set(scope);
                checkUi(scope);
                beginNative(scope);
                started = true;
                return callable.call();
            } catch (ApiException e) {
                apiError.set(e);
                throw e;
            } finally {
                if (started) nativeBusy.set(false);
                RequestScope.CURRENT.remove();
            }
        });
        main.post(task);
        try {
            T result = task.get(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            ApiException error = apiError.get();
            if (error != null) {
                throw error;
            }
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            task.cancel(false);
            main.removeCallbacks(task);
            throw new ApiException("TIMEOUT", "Accessibility operation timed out");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException) {
                throw (ApiException) cause;
            }
            throw new ApiException("INTERNAL", "Accessibility operation failed");
        } catch (InterruptedException e) {
            task.cancel(false);
            main.removeCallbacks(task);
            Thread.currentThread().interrupt();
            throw new ApiException("TIMEOUT", "Accessibility operation interrupted");
        }
    }

    private static String capped(CharSequence value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        return text.length() <= MAX_NODE_TEXT ? text : text.substring(0, MAX_NODE_TEXT);
    }

    private interface ThrowingCallable<T> {
        T call() throws ApiException;
    }

    private static final class Counter {
        int count;
        boolean truncated;
    }

    private static final class CompactCounter {
        final int limit;
        int count;
        boolean truncated;
        boolean webViewDetected;
        CompactCounter(int limit) { this.limit = limit; }
    }

    private static final class MatchCounter {
        final int limit;
        int seen;
        boolean truncated;
        MatchCounter(int limit) { this.limit = limit; }
    }

    private static class ActionResult {
        final int target;
        int current;
        int scanned;
        boolean found;
        boolean performed;
        ActionResult(int target) { this.target = target; }
    }

    private static final class TextResult extends ActionResult {
        final String text;
        TextResult(int target, String text) {
            super(target);
            this.text = text;
        }
    }

    private static final class UiSelector {
        final String text;
        final String textContains;
        final String description;
        final String descriptionContains;
        final String viewId;
        final String className;
        final String packageName;
        final Boolean clickable;
        final Boolean editable;
        final Boolean enabled;
        final Boolean visible;
        final boolean caseSensitive;

        private UiSelector(JSONObject object) throws ApiException {
            JsonArgs.only(object, "text", "textContains", "description", "descriptionContains",
                    "viewId", "className", "packageName", "clickable", "editable", "enabled",
                    "visible", "caseSensitive");
            text = optional(object, "text");
            textContains = optional(object, "textContains");
            description = optional(object, "description");
            descriptionContains = optional(object, "descriptionContains");
            viewId = optional(object, "viewId");
            className = optional(object, "className");
            packageName = optional(object, "packageName");
            clickable = optionalBool(object, "clickable");
            editable = optionalBool(object, "editable");
            enabled = optionalBool(object, "enabled");
            visible = optionalBool(object, "visible");
            caseSensitive = JsonArgs.optionalBoolean(object, "caseSensitive", false);
            if (text == null && textContains == null && description == null && descriptionContains == null
                    && viewId == null && className == null && packageName == null
                    && clickable == null && editable == null && enabled == null && visible == null) {
                throw new ApiException("INVALID_ARGUMENT", "Selector needs at least one criterion");
            }
        }

        static UiSelector from(JSONObject object) throws ApiException { return new UiSelector(object); }

        boolean matches(AccessibilityNodeInfo node) {
            if (!matchExact(text, node.isPassword() ? "" : value(node.getText()))) return false;
            if (!matchContains(textContains, node.isPassword() ? "" : value(node.getText()))) return false;
            if (!matchExact(description, node.isPassword() ? "" : value(node.getContentDescription()))) return false;
            if (!matchContains(descriptionContains, node.isPassword() ? "" : value(node.getContentDescription()))) return false;
            if (!matchExact(viewId, value(node.getViewIdResourceName()))) return false;
            if (!matchExact(className, value(node.getClassName()))) return false;
            if (!matchExact(packageName, value(node.getPackageName()))) return false;
            if (clickable != null && clickable != node.isClickable()) return false;
            if (editable != null && editable != node.isEditable()) return false;
            if (enabled != null && enabled != node.isEnabled()) return false;
            if (visible != null && visible != node.isVisibleToUser()) return false;
            return true;
        }

        private boolean matchExact(String expected, String actual) {
            if (expected == null) return true;
            return caseSensitive ? expected.equals(actual) : expected.equalsIgnoreCase(actual);
        }

        private boolean matchContains(String expected, String actual) {
            if (expected == null) return true;
            if (caseSensitive) return actual.contains(expected);
            return actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
        }

        private static String optional(JSONObject object, String key) throws ApiException {
            return object.has(key)
                    ? JsonArgs.requiredStringAllowEmpty(object, key, SecurityValidators.MAX_SELECTOR_TEXT)
                    : null;
        }

        private static Boolean optionalBool(JSONObject object, String key) throws ApiException {
            return object.has(key) ? JsonArgs.requiredBoolean(object, key) : null;
        }

        private static String value(CharSequence value) { return value == null ? "" : value.toString(); }
    }

}
