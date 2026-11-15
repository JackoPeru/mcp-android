package com.example.androidmcp;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;

/**
 * MediaProjection recorder. The system consent dialog is mandatory and is
 * requested by {@link ScreenRecordActivity}; this service starts as a
 * mediaProjection foreground service *before* obtaining the projection, as
 * Android 14 requires, and releases everything on stop/destroy.
 */
public final class ScreenRecordService extends Service {
    private static final int NOTIFICATION_ID = 43;
    private static final String CHANNEL = "capture";
    private MediaProjection projection;
    private MediaRecorder recorder;
    private VirtualDisplay display;
    private File file;
    private long maxDurationMs;
    private boolean recording;
    private boolean stopping;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stopRecording;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Acquisizioni", NotificationManager.IMPORTANCE_LOW));
    }

    // The display density is a runtime value, not one of the DENSITY_* aliases
    // the annotation expects; createVirtualDisplay accepts any positive dpi.
    @SuppressLint("WrongConstant")
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (recording) return START_NOT_STICKY;
        startForeground(NOTIFICATION_ID, notification("Registrazione schermo in corso"),
                Build.VERSION.SDK_INT >= 34
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        : ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
        if (intent == null || intent.getParcelableExtra("data") == null) {
            ScreenRecordController.onError("SCREEN_RECORD_CONSENT_MISSING");
            stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        int fps = Math.max(10, Math.min(60, intent.getIntExtra("fps", 30)));
        int bitrate = Math.max(500_000, Math.min(20_000_000, intent.getIntExtra("bitrate", 4_000_000)));
        int maxLongEdge = Math.max(0, intent.getIntExtra("maxLongEdge", 1280));
        maxDurationMs = Math.max(1000, intent.getLongExtra("maxDurationMs", 60_000));
        DisplayMetrics metrics = screenMetrics();
        int[] size = scaledSize(metrics.widthPixels, metrics.heightPixels, maxLongEdge);
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) throw new IllegalStateException("no projection");
            file = CaptureStore.newFile(this, "screen", "mp4");
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(size[0], size[1]);
            recorder.setVideoFrameRate(fps);
            recorder.setVideoEncodingBitRate(bitrate);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            display = projection.createVirtualDisplay("mcp-screen", size[0], size[1],
                    metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(), null, handler);
            recorder.start();
            recording = true;
            ScreenRecordController.onStarted(file.getName(), size[0], size[1], fps, bitrate, maxDurationMs);
            handler.postDelayed(autoStop, maxDurationMs);
        } catch (Exception e) {
            release();
            ScreenRecordController.onError("SCREEN_RECORD_START_FAILED");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private DisplayMetrics screenMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager window = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30 && window != null) {
            android.graphics.Rect bounds = window.getMaximumWindowMetrics().getBounds();
            metrics.widthPixels = bounds.width();
            metrics.heightPixels = bounds.height();
            metrics.densityDpi = getResources().getDisplayMetrics().densityDpi;
        } else if (window != null) {
            //noinspection deprecation
            window.getDefaultDisplay().getRealMetrics(metrics);
        }
        if (metrics.widthPixels <= 0) { metrics.widthPixels = 1080; metrics.heightPixels = 1920; }
        if (metrics.densityDpi <= 0) metrics.densityDpi = DisplayMetrics.DENSITY_420;
        return metrics;
    }

    /** Even dimensions are required by several H.264 encoders. */
    static int[] scaledSize(int width, int height, int maxLongEdge) {
        int longEdge = Math.max(width, height);
        float scale = maxLongEdge > 0 && longEdge > maxLongEdge ? maxLongEdge / (float) longEdge : 1f;
        int w = Math.max(2, Math.round(width * scale / 2f) * 2);
        int h = Math.max(2, Math.round(height * scale / 2f) * 2);
        return new int[]{w, h};
    }

    private void stopRecording() {
        if (stopping) return;
        stopping = true;
        handler.removeCallbacks(autoStop);
        boolean interrupted = !recording;
        try {
            if (recording) recorder.stop();
        } catch (RuntimeException e) {
            if (file != null) //noinspection ResultOfMethodCallIgnored
                file.delete();
            if (!interrupted) ScreenRecordController.onError("SCREEN_RECORD_EMPTY");
        } finally {
            release();
        }
        ScreenRecordController.onFinished(interrupted, interrupted ? "SCREEN_RECORD_INTERRUPTED" : "");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void release() {
        recording = false;
        if (display != null) {
            try { display.release(); } catch (RuntimeException ignored) { }
            display = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (RuntimeException ignored) { }
            projection = null;
        }
        if (recorder != null) {
            try { recorder.reset(); } catch (RuntimeException ignored) { }
            recorder.release();
            recorder = null;
        }
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ScreenRecordService.class).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("MCP Android")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recording) {
            try { recorder.stop(); } catch (RuntimeException e) {
                if (file != null) //noinspection ResultOfMethodCallIgnored
                    file.delete();
            }
            release();
            ScreenRecordController.onFinished(true, "SCREEN_RECORD_INTERRUPTED");
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
