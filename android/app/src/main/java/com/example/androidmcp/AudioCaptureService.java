package com.example.androidmcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;

/**
 * Visible microphone recording: foreground service of type microphone with an
 * ongoing notification and a Stop action. Started only from
 * {@link AudioCaptureActivity}, so Android's while-in-use rules hold.
 */
public final class AudioCaptureService extends Service {
    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL = "capture";
    private MediaRecorder recorder;
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

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (recording) return START_NOT_STICKY;
        startForeground(NOTIFICATION_ID, notification("Registrazione audio in corso"),
                Build.VERSION.SDK_INT >= 34
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        : ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
        maxDurationMs = intent == null ? 60_000 : Math.max(1000, intent.getLongExtra("maxDurationMs", 60_000));
        String ext = intent == null ? "m4a" : String.valueOf(intent.getStringExtra("ext"));
        int bitrate = intent == null ? 96_000 : intent.getIntExtra("bitrate", 96_000);
        file = CaptureStore.newFile(this, "audio", ext);
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            if ("ogg".equals(ext)) {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            } else {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            }
            recorder.setAudioSamplingRate(44_100);
            recorder.setAudioEncodingBitRate(Math.min(320_000, Math.max(16_000, bitrate)));
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            AudioController.onStarted(file, maxDurationMs);
            handler.postDelayed(autoStop, maxDurationMs);
        } catch (Exception e) {
            release();
            AudioController.onError("MIC_RECORD_START_FAILED");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void stopRecording() {
        if (stopping) return;
        stopping = true;
        handler.removeCallbacks(autoStop);
        boolean interrupted = !recording;
        finalizeFile(interrupted);
        AudioController.onFinished(interrupted, interrupted ? "MIC_INTERRUPTED" : "");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void finalizeFile(boolean interrupted) {
        if (recorder == null) return;
        try {
            if (recording) recorder.stop();
        } catch (RuntimeException e) {
            // No frames captured: the file is unusable, remove it.
            if (file != null) //noinspection ResultOfMethodCallIgnored
                file.delete();
            if (!interrupted) AudioController.onError("MIC_RECORD_EMPTY");
        } finally {
            release();
        }
    }

    private void release() {
        recording = false;
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
                new Intent(this, AudioCaptureService.class).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
            finalizeFile(true);
            AudioController.onFinished(true, "MIC_INTERRUPTED");
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
