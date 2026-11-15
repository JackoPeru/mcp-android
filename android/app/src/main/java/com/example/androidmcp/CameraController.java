package com.example.androidmcp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;

/**
 * Camera capture orchestration plus torch control.
 *
 * <p>Capture is always visible: {@link CameraCaptureActivity} owns the CameraX
 * session and shows a live preview, so nothing is taken from the background
 * and Android's foreground camera rules are respected. The RPC thread waits
 * for the activity result with a bounded timeout.
 */
public final class CameraController {
    static final Object LOCK = new Object();
    static volatile JSONObject result;
    static volatile String error;
    static volatile boolean active;
    static volatile boolean cancelRequested;
    private static volatile boolean torchOn;

    private CameraController() { }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    public static JSONObject capture(Context context, String lens, String flash,
                                     int maxWidth, int quality, long timeoutMs) throws ApiException {
        if (!hasPermission(context)) {
            throw new ApiException("CAMERA_PERMISSION_DENIED", "Grant the camera permission in MCP Android");
        }
        String lensValue = lens == null ? "back" : lens;
        if (!Arrays.asList("back", "front").contains(lensValue)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid camera lens");
        }
        String flashValue = flash == null ? "auto" : flash;
        if (!Arrays.asList("auto", "on", "off").contains(flashValue)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid flash mode");
        }
        CaptureStore.cleanup(context);
        synchronized (LOCK) {
            if (active) throw new ApiException("BUSY", "Another camera capture is active");
            active = true;
            cancelRequested = false;
            result = null;
            error = null;
            long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
            try {
                Intent intent = new Intent(context, CameraCaptureActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("lens", lensValue)
                        .putExtra("flash", flashValue)
                        .putExtra("maxWidth", maxWidth)
                        .putExtra("quality", quality);
                context.startActivity(intent);
            } catch (RuntimeException e) {
                active = false;
                throw new ApiException("CAMERA_UNAVAILABLE", "Unable to open the capture activity");
            }
            try {
                while (result == null && error == null) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    LOCK.wait(Math.min(remaining, 500));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (result != null) {
                active = false;
                return result;
            }
            if (error != null) {
                active = false;
                String code = error;
                error = null;
                throw new ApiException(code, "Camera capture failed");
            }
            // The user may still be approving; close the activity and report
            // an explicit timeout instead of pretending a photo exists.
            cancelRequested = true;
            active = false;
            throw new ApiException("CAMERA_TIMEOUT", "Camera capture did not finish in time");
        }
    }

    static void complete(JSONObject value) {
        synchronized (LOCK) {
            result = value;
            LOCK.notifyAll();
        }
    }

    static void fail(String code) {
        synchronized (LOCK) {
            error = code;
            LOCK.notifyAll();
        }
    }

    static boolean cancelled() { return cancelRequested; }

    static File newPhotoFile(Context context) {
        return CaptureStore.newFile(context, "photo", "jpg");
    }

    public static JSONObject torch(Context context, boolean enabled) throws ApiException {
        if (!hasPermission(context)) {
            throw new ApiException("CAMERA_PERMISSION_DENIED", "Grant the camera permission in MCP Android");
        }
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) throw new ApiException("CAMERA_UNAVAILABLE", "Camera service unavailable");
        try {
            String target = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Boolean flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    target = id;
                    break;
                }
                if (Boolean.TRUE.equals(flash) && target == null) target = id;
            }
            if (target == null) throw new ApiException("TORCH_UNAVAILABLE", "No camera with flash");
            manager.setTorchMode(target, enabled);
            torchOn = enabled;
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("torch", enabled);
            return result;
        } catch (CameraAccessException | IllegalArgumentException e) {
            throw new ApiException("TORCH_UNAVAILABLE", "Unable to control the torch");
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode torch state");
        }
    }

    public static JSONObject torchStatus(Context context) throws ApiException {
        JSONObject result = new JSONObject();
        try {
            boolean available = false;
            if (hasPermission(context)) {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (manager != null) {
                    for (String id : manager.getCameraIdList()) {
                        Boolean flash = manager.getCameraCharacteristics(id)
                                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                        if (Boolean.TRUE.equals(flash)) { available = true; break; }
                    }
                }
            }
            result.put("permission", hasPermission(context));
            result.put("available", available);
            result.put("torch", torchOn);
            return result;
        } catch (CameraAccessException e) {
            throw new ApiException("CAMERA_UNAVAILABLE", "Camera service unavailable");
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode torch state");
        }
    }

    static void setTorchState(boolean on) { torchOn = on; }
}
