package com.example.androidmcp;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/**
 * Full-screen tint shown while the remote session is active, so anyone holding
 * the phone sees that an agent is watching and acting.
 *
 * <p>Deliberately passive: not focusable, not touchable, no touch modal — it
 * can neither eat the agent's gestures nor trap the user's. Alpha is kept low
 * so screenshots stay readable for the agent itself. Requires the "Display
 * over other apps" special access; without it the session works exactly as
 * before, just without the veil (see {@link #canShow}).
 */
public final class SessionVeil {
    private static final int TINT = 0x2E64DFC4;
    private static final long FRAME_SETTLE_MS = 60;
    private static View veil;
    private static WindowManager.LayoutParams lastParams;

    private SessionVeil() { }

    public static boolean canShow(Context context) {
        try {
            return Settings.canDrawOverlays(context);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void show(Context context) {
        synchronized (SessionVeil.class) {
            if (veil != null || !canShow(context)) return;
            try {
                Context app = context.getApplicationContext();
                WindowManager manager = app.getSystemService(WindowManager.class);
                if (manager == null) return;
                View view = new View(app);
                view.setBackgroundColor(TINT);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                manager.addView(view, params);
                veil = view;
                lastParams = params;
            } catch (RuntimeException ignored) {
                veil = null;
            }
        }
    }

    /**
     * Removes the veil for exactly one screenshot frame so the agent never
     * sees it, then the caller must invoke {@link #restoreAfterCapture} in a
     * finally block. Returns the live view, or null when there is nothing to
     * hide. If the session stops mid-capture, restore becomes a no-op and the
     * veil stays down, which is the correct end state.
     */
    public static View suspendForCapture() {
        final View view;
        synchronized (SessionVeil.class) {
            view = veil;
        }
        if (view == null) return null;
        final java.util.concurrent.CountDownLatch removed =
                new java.util.concurrent.CountDownLatch(1);
        Runnable hide = () -> {
            try {
                WindowManager manager = (WindowManager) view.getContext()
                        .getSystemService(WindowManager.class);
                if (manager != null) manager.removeViewImmediate(view);
            } catch (RuntimeException ignored) {
                // Already gone or permission revoked mid-session: capture
                // proceeds, restore below re-adds only if still ours.
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
        // Let one frame compose without the layer before capturing.
        try { Thread.sleep(FRAME_SETTLE_MS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return view;
    }

    public static void restoreAfterCapture(Context context, View view) {
        final WindowManager.LayoutParams params;
        synchronized (SessionVeil.class) {
            // If the session stopped mid-capture, hide() already cleared the
            // reference: staying hidden is the correct end state.
            if (view == null || veil != view) return;
            params = lastParams;
        }
        if (params == null) return;
        try {
            WindowManager manager = ((Context) context.getApplicationContext())
                    .getSystemService(WindowManager.class);
            if (manager != null) manager.addView(view, params);
        } catch (RuntimeException ignored) {
            // Already re-added by a concurrent capture, session stopped, or
            // overlay permission revoked: all safe end states, never a leak
            // here since the live reference stays in `veil` for the next hide.
        }
    }

    public static void hide() {
        final View view;
        synchronized (SessionVeil.class) {
            view = veil;
            veil = null;
        }
        if (view == null) return;
        Runnable remove = () -> {
            try {
                WindowManager manager = (WindowManager) view.getContext().getSystemService(WindowManager.class);
                if (manager != null) manager.removeView(view);
            } catch (RuntimeException ignored) { }
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
}
