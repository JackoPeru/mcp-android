package com.example.androidmcp;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Visible CameraX capture: preview on screen, one shot, then finish. */
public final class CameraCaptureActivity extends Activity implements LifecycleOwner {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private ExecutorService io;
    private ProcessCameraProvider provider;
    private boolean finished;

    @Override public Lifecycle getLifecycle() { return lifecycle; }

    @Override protected void onStart() {
        super.onStart();
        lifecycle.setCurrentState(Lifecycle.State.STARTED);
    }

    @Override protected void onResume() {
        super.onResume();
        lifecycle.setCurrentState(Lifecycle.State.RESUMED);
    }

    @Override protected void onPause() {
        lifecycle.setCurrentState(Lifecycle.State.STARTED);
        super.onPause();
    }

    @Override protected void onStop() {
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
        super.onStop();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
        io = Executors.newSingleThreadExecutor();
        if (CameraController.cancelled()) { finish(); return; }
        if (!CameraController.hasPermission(this)) {
            CameraController.fail("CAMERA_PERMISSION_DENIED");
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        PreviewView preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView hint = new TextView(this);
        hint.setText("Scatto in corso…");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintParams.bottomMargin = Math.round(48 * getResources().getDisplayMetrics().density);
        root.addView(hint, hintParams);
        setContentView(root);
        bind(preview);
        handler.postDelayed(() -> { if (!finished) { CameraController.fail("CAMERA_TIMEOUT"); finish(); } }, 15_000);
    }

    private void bind(PreviewView preview) {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
                boolean front = "front".equals(getIntent().getStringExtra("lens"));
                CameraSelector selector = front
                        ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : CameraSelector.DEFAULT_BACK_CAMERA;
                Preview previewUseCase = new Preview.Builder().build();
                previewUseCase.setSurfaceProvider(preview.getSurfaceProvider());
                ImageCapture capture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                String flash = String.valueOf(getIntent().getStringExtra("flash"));
                capture.setFlashMode("on".equals(flash) ? ImageCapture.FLASH_MODE_ON
                        : "off".equals(flash) ? ImageCapture.FLASH_MODE_OFF
                        : ImageCapture.FLASH_MODE_AUTO);
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, previewUseCase, capture);
                handler.postDelayed(() -> shoot(capture), 450);
            } catch (Exception e) {
                CameraController.fail("CAMERA_UNAVAILABLE");
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void shoot(ImageCapture capture) {
        if (finished) return;
        if (CameraController.cancelled()) { finish(); return; }
        File file = CameraController.newPhotoFile(this);
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(file).build();
        capture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override public void onImageSaved(ImageCapture.OutputFileResults output) {
                        io.execute(() -> finishWithFile(file));
                    }
                    @Override public void onError(ImageCaptureException exception) {
                        CameraController.fail("CAMERA_CAPTURE_FAILED");
                        markFinished();
                        finish();
                    }
                });
    }

    private void finishWithFile(File file) {
        try {
            int maxWidth = Math.max(0, getIntent().getIntExtra("maxWidth", 0));
            int quality = Math.min(100, Math.max(40, getIntent().getIntExtra("quality", 90)));
            if (maxWidth > 0) file = downscale(file, maxWidth, quality);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            JSONObject value = new JSONObject();
            value.put("ok", true);
            value.put("name", file.getName());
            value.put("mimeType", CaptureStore.mimeType(file.getName()));
            value.put("width", bounds.outWidth);
            value.put("height", bounds.outHeight);
            value.put("size", file.length());
            value.put("lens", String.valueOf(getIntent().getStringExtra("lens")));
            CameraController.complete(value);
        } catch (Exception e) {
            CameraController.fail("CAMERA_CAPTURE_FAILED");
        }
        markFinished();
        runOnUiThread(this::finish);
    }

    /** Rewrites the JPEG in place (same name) when a client width cap is set. */
    private File downscale(File source, int maxWidth, int quality) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getPath(), bounds);
            if (bounds.outWidth <= maxWidth) return source;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(source.getPath(), options);
            if (bitmap == null) return source;
            float ratio = maxWidth / (float) bitmap.getWidth();
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, maxWidth,
                    Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
            try (FileOutputStream output = new FileOutputStream(source)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, output);
            }
            if (scaled != bitmap) bitmap.recycle();
            scaled.recycle();
            return source;
        } catch (Exception e) {
            return source;
        }
    }

    private void markFinished() {
        finished = true;
        if (provider != null) try { provider.unbindAll(); } catch (RuntimeException ignored) { }
    }

    @Override protected void onDestroy() {
        lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
        if (!finished) {
            // User backed out: unblock the waiting RPC now instead of at timeout.
            CameraController.fail("CAMERA_CANCELLED");
        }
        markFinished();
        if (io != null) io.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
