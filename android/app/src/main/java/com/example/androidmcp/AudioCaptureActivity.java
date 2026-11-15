package com.example.androidmcp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Visible recording screen: live state, elapsed time and a Stop button. */
public final class AudioCaptureActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView stateView;
    private TextView timerView;
    private long startedAt;
    private boolean stopSent;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            String state = AudioController.state;
            if ("idle".equals(state) || "interrupted".equals(state)) { finish(); return; }
            stateView.setText("recording".equals(state) ? "Registrazione in corso"
                    : "stopping".equals(state) ? "Arresto…" : "Avvio microfono…");
            if (startedAt > 0) {
                long elapsed = System.currentTimeMillis() - startedAt;
                timerView.setText(String.format(java.util.Locale.US, "%02d:%02d",
                        elapsed / 60000, (elapsed / 1000) % 60));
            }
            handler.postDelayed(this, 300);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xff0b1217);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView title = new TextView(this);
        title.setText("MCP Android · Audio");
        title.setTextColor(0xff64dfc4);
        title.setTextSize(14);
        root.addView(title);
        stateView = new TextView(this);
        stateView.setText("Avvio microfono…");
        stateView.setTextColor(0xffedf5f5);
        stateView.setTextSize(20);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stateParams.topMargin = dp(16);
        root.addView(stateView, stateParams);
        timerView = new TextView(this);
        timerView.setText("00:00");
        timerView.setTextColor(0xffa2b4bd);
        timerView.setTextSize(40);
        LinearLayout.LayoutParams timerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timerParams.topMargin = dp(8);
        root.addView(timerView, timerParams);
        Button stop = new Button(this);
        stop.setText("Interrompi registrazione");
        stop.setAllCaps(false);
        stop.setOnClickListener(v -> stopNow());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        stopParams.topMargin = dp(24);
        root.addView(stop, stopParams);
        setContentView(root);
        if (isFinishing()) return;
        Intent service = new Intent(this, AudioCaptureService.class)
                .putExtra("ext", getIntent().getStringExtra("ext"))
                .putExtra("maxDurationMs", getIntent().getLongExtra("maxDurationMs", 60_000))
                .putExtra("bitrate", getIntent().getIntExtra("bitrate", 96_000));
        try {
            startForegroundService(service);
        } catch (RuntimeException e) {
            AudioController.onError("MIC_RECORD_START_FAILED");
            finish();
            return;
        }
        handler.postDelayed(() -> {
            startedAt = System.currentTimeMillis();
            handler.post(tick);
        }, 200);
    }

    private void stopNow() {
        if (stopSent) return;
        stopSent = true;
        startService(new Intent(this, AudioCaptureService.class).setAction("STOP"));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
