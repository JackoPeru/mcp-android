package com.example.androidmcp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

/**
 * Animated session indicators shown while the remote session is active, so
 * anyone holding the phone sees that an agent is watching and acting.
 *
 * <p>Two overlay windows, both invisible to the agent's screenshots (see
 * {@link #suspendForCapture}):
 * <ul>
 *   <li>a full-screen wave animation, deliberately NOT touchable: a touchable
 *   full-screen layer would also swallow the agent's own injected gestures,
 *   which travel through the same input pipeline;</li>
 *   <li>a small touchable STOP pill at bottom-center that instantly stops the
 *   session if the agent misbehaves.</li>
 * </ul>
 * Without the "Display over other apps" special access the session works
 * exactly as before, just without indicators (see {@link #canShow}).
 */
public final class SessionVeil {
    private static final long FRAME_MS = 50;
    private static final int BASE_TINT = 0x143AA8C8;
    /** Celestine edge glows sampled from the reference takeover effect. */
    private static final int[] GLOW_COLORS = {0x4DB8D8, 0x6FE3F0, 0x2E7FD8, 0x4DB8D8};
    private static final float[] GLOW_X = {0.08f, 0.95f, 0.02f, 0.90f};
    private static final float[] GLOW_Y = {0.10f, 0.05f, 0.90f, 0.85f};
    /** Slow clockwise orbit around each anchor: radius as a fraction of the
     *  smaller side, period in seconds per revolution, initial phase. */
    private static final float[] ORBIT_R = {0.15f, 0.12f, 0.13f, 0.16f};
    private static final float[] ORBIT_T = {5f, 7f, 10f, 6f};
    private static final float[] ORBIT_P0 = {0f, 1.7f, 3.4f, 5.1f};

    /**
     * Idle gap after the last UI RPC before the indicators fade. Deliberately
     * long: an agent thinks between micro-steps (tens of seconds), and the
     * veil must stay continuous for the whole task, fading only well after it
     * really ends. The STOP pill and notification action remain available to
     * cut it short at any moment.
     */
    static final long IDLE_MS = 90_000;

    private static View veil;
    private static View stopPill;
    private static WindowManager.LayoutParams veilParams;
    private static WindowManager.LayoutParams pillParams;
    private static long generation;

    private SessionVeil() { }

    /**
     * Shows the indicators now and schedules them to fade {@link #IDLE_MS}
     * after the last pulse. Called on every UI-touching RPC, so the veil is
     * up exactly while the agent works on screen and a while after it stops.
     */
    public static void pulse(Context context) {
        final long current;
        synchronized (SessionVeil.class) {
            current = ++generation;
        }
        show(context);
        try {
            Looper main = Looper.getMainLooper();
            if (main == null) return;
            new Handler(main).postDelayed(() -> {
                synchronized (SessionVeil.class) {
                    if (current != generation) return;
                }
                hide();
            }, IDLE_MS);
        } catch (RuntimeException ignored) { }
    }

    public static boolean canShow(Context context) {
        try {
            return Settings.canDrawOverlays(context);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void show(Context context) {
        // addView creates a ViewRootImpl, which requires the main thread, but
        // pulse() runs on RPC worker threads without a Looper: without this
        // hop the add dies silently and the veil never appears.
        try {
            Looper main = Looper.getMainLooper();
            if (main != null && Thread.currentThread() != main.getThread()) {
                final java.util.concurrent.CountDownLatch done =
                        new java.util.concurrent.CountDownLatch(1);
                new Handler(main).post(() -> {
                    try { showNow(context); } finally { done.countDown(); }
                });
                done.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException ignored) {
            return;
        }
        showNow(context);
    }

    private static void showNow(Context context) {
        synchronized (SessionVeil.class) {
            if ((veil != null && stopPill != null) || !canShow(context)) return;
            try {
                Context app = context.getApplicationContext();
                WindowManager manager = app.getSystemService(WindowManager.class);
                if (manager == null) return;
                if (veil == null) {
                    SeaGlowView waves = new SeaGlowView(app);
                    veilParams = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT);
                    manager.addView(waves, veilParams);
                    veil = waves;
                }
                if (stopPill == null) {
                    Button stop = new Button(app);
                    stop.setText("■ Stop");
                    stop.setContentDescription("Interrompi subito l'agente");
                    stop.setTextColor(0xFFFFFFFF);
                    stop.setTextSize(15);
                    stop.setAllCaps(false);
                    android.graphics.drawable.GradientDrawable pill =
                            new android.graphics.drawable.GradientDrawable();
                    pill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                    pill.setCornerRadius(dp(app, 28));
                    pill.setColor(0xDD101528);
                    pill.setStroke(dp(app, 2), 0xFF64DFC4);
                    stop.setBackground(pill);
                    int padH = dp(app, 28);
                    int padV = dp(app, 12);
                    stop.setPadding(padH, padV, padH, padV);
                    stop.setOnClickListener(ignored -> McpForegroundService.stopNow());
                    pillParams = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT);
                    pillParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    pillParams.y = dp(app, 72);
                    manager.addView(stop, pillParams);
                    stopPill = stop;
                }
            } catch (RuntimeException ignored) {
                hide();
            }
        }
    }

    /**
     * Fades the indicators immediately, e.g. when the agent signals its
     * on-screen work is done. Any late delayed hide becomes a no-op via the
     * generation check.
     */
    public static void cancel() {
        synchronized (SessionVeil.class) {
            generation++;
        }
        hide();
    }

    public static void hide() {
        final View[] pair;
        synchronized (SessionVeil.class) {
            pair = new View[]{veil, stopPill};
            veil = null;
            stopPill = null;
        }
        Runnable remove = () -> {
            for (View view : pair) {
                if (view == null) continue;
                try {
                    WindowManager manager = (WindowManager) view.getContext()
                            .getSystemService(WindowManager.class);
                    if (manager != null) manager.removeView(view);
                } catch (RuntimeException ignored) { }
            }
        };
        // WindowManager calls belong on the main thread; enterLowPowerIdle can
        // run from RPC worker threads.
        try {
            Looper main = Looper.getMainLooper();
            if (main != null && Thread.currentThread() != main.getThread()) {
                new Handler(main).post(remove);
                return;
            }
        } catch (RuntimeException ignored) { }
        remove.run();
    }

    /**
     * Removes every indicator for exactly one screenshot frame so the agent
     * never sees them, then the caller must invoke {@link #restoreAfterCapture}
     * in a finally block. If the session stops mid-capture, restore becomes a
     * no-op and the indicators stay down, which is the correct end state.
     *
     * @return opaque token for {@link #restoreAfterCapture}, null when idle.
     */
    public static Object suspendForCapture() {
        final View[] pair;
        synchronized (SessionVeil.class) {
            if (veil == null && stopPill == null) return null;
            pair = new View[]{veil, stopPill};
        }
        final java.util.concurrent.CountDownLatch removed =
                new java.util.concurrent.CountDownLatch(1);
        Runnable hide = () -> {
            try {
                for (View view : pair) {
                    if (view == null) continue;
                    try {
                        WindowManager manager = (WindowManager) view.getContext()
                                .getSystemService(WindowManager.class);
                        if (manager != null) manager.removeViewImmediate(view);
                    } catch (RuntimeException ignored) { }
                }
            } finally {
                removed.countDown();
            }
        };
        try {
            Looper main = Looper.getMainLooper();
            if (main != null && Thread.currentThread() != main.getThread()) {
                new Handler(main).post(hide);
                removed.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                hide.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) { }
        // Let one frame compose without the layers before capturing.
        try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return pair;
    }

    public static void restoreAfterCapture(Context context, Object token) {
        if (!(token instanceof View[])) return;
        final View[] pair = (View[]) token;
        final WindowManager.LayoutParams[] layouts;
        synchronized (SessionVeil.class) {
            // Session stopped mid-capture, or a concurrent capture already
            // restored: staying as-is is the correct end state in both cases.
            if ((veil != null && veil != pair[0]) || (stopPill != null && stopPill != pair[1])) return;
            layouts = new WindowManager.LayoutParams[]{veilParams, pillParams};
        }
        if (layouts[0] == null) return;
        // addView creates a ViewRootImpl, which requires the main thread, but
        // screenshot() runs on RPC worker threads without a Looper: posting
        // is mandatory, otherwise the add silently dies here and the veil
        // never comes back.
        final java.util.concurrent.CountDownLatch added =
                new java.util.concurrent.CountDownLatch(1);
        Runnable restore = () -> {
            try {
                WindowManager manager = ((Context) context.getApplicationContext())
                        .getSystemService(WindowManager.class);
                if (manager == null) return;
                for (int i = 0; i < pair.length; i++) {
                    if (pair[i] == null || layouts[i] == null) continue;
                    try {
                        manager.addView(pair[i], layouts[i]);
                        synchronized (SessionVeil.class) {
                            if (i == 0) veil = pair[i]; else stopPill = pair[i];
                        }
                    } catch (RuntimeException ignored) {
                        // Already re-added concurrently, session stopped, or
                        // overlay permission revoked: all safe end states.
                    }
                }
            } finally {
                added.countDown();
            }
        };
        try {
            Looper main = Looper.getMainLooper();
            if (main != null && Thread.currentThread() != main.getThread()) {
                new Handler(main).post(restore);
                added.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                restore.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) { }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Sea-surface takeover glow: a faint celestine wash plus large radial
     * glows drifting slowly along the edges, like light on water seen from
     * above. Glow bitmaps are pre-rendered once; each frame only moves them
     * and breathes their alpha. Animates only while attached.
     */
    static final class SeaGlowView extends View {
        private static final int GLOW_PX = 256;
        private final Paint base = new Paint();
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Bitmap[] glows = new android.graphics.Bitmap[GLOW_COLORS.length];
        private final android.graphics.RectF dst = new android.graphics.RectF();
        private final Handler frames = new Handler(Looper.getMainLooper());
        private float time;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                time += FRAME_MS / 1000f;
                invalidate();
                frames.postDelayed(this, FRAME_MS);
            }
        };

        SeaGlowView(Context context) {
            super(context);
            base.setColor(BASE_TINT);
            for (int i = 0; i < GLOW_COLORS.length; i++) {
                glows[i] = glowBitmap(GLOW_COLORS[i]);
            }
        }

        private static android.graphics.Bitmap glowBitmap(int color) {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                    GLOW_PX, GLOW_PX, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            int red = (color >> 16) & 0xFF;
            int green = (color >> 8) & 0xFF;
            int blue = color & 0xFF;
            paint.setShader(new android.graphics.RadialGradient(
                    GLOW_PX / 2f, GLOW_PX / 2f, GLOW_PX / 2f,
                    new int[]{
                            android.graphics.Color.argb(255, red, green, blue),
                            android.graphics.Color.argb(110, red, green, blue),
                            android.graphics.Color.argb(0, red, green, blue)},
                    new float[]{0f, 0.45f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, GLOW_PX, GLOW_PX, paint);
            return bitmap;
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            // The same instance is re-added after every screenshot suspension,
            // so bitmaps must survive detach: recreate only if missing.
            for (int i = 0; i < glows.length; i++) {
                if (glows[i] == null || glows[i].isRecycled()) glows[i] = glowBitmap(GLOW_COLORS[i]);
            }
            frames.post(tick);
        }

        @Override protected void onDetachedFromWindow() {
            frames.removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;
            canvas.drawRect(0, 0, width, height, base);
            float radius = Math.min(width, height) * 0.62f;
            float orbitBase = Math.min(width, height);
            for (int i = 0; i < glows.length; i++) {
                if (glows[i] == null || glows[i].isRecycled()) continue;
                // Screen Y grows downward, so an increasing angle reads as
                // clockwise on screen.
                float angle = ORBIT_P0[i] + time * (float) (2 * Math.PI) / ORBIT_T[i];
                float orbit = ORBIT_R[i] * orbitBase;
                float cx = GLOW_X[i] * width + orbit * (float) Math.cos(angle);
                float cy = GLOW_Y[i] * height + orbit * (float) Math.sin(angle);
                int alpha = 0x50 + Math.round(0x1A * (float) Math.sin(time * (0.5f + 0.13f * i) + i));
                glowPaint.setAlpha(Math.max(0x28, Math.min(0x8C, alpha)));
                dst.set(cx - radius, cy - radius, cx + radius, cy + radius);
                canvas.drawBitmap(glows[i], null, dst, glowPaint);
            }
        }
    }
}
