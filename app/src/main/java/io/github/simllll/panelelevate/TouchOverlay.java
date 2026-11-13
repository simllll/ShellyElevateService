package io.github.simllll.panelelevate;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Touch detection without access to /dev/input (NSPanel Pro without root): a 1x1 px, untouchable
 * system overlay with FLAG_WATCH_OUTSIDE_TOUCH receives ACTION_OUTSIDE for every touch-down on
 * the screen, while the touch itself still goes to the app underneath.
 *
 * Needs SYSTEM_ALERT_WINDOW, granted via adb:
 * {@code appops set io.github.simllll.panelelevate SYSTEM_ALERT_WINDOW allow}.
 * TYPE_SYSTEM_ALERT is fine for our targetSdk 24 (TYPE_APPLICATION_OVERLAY is only enforced from
 * targetSdk 26).
 */
public class TouchOverlay {
    private static final String TAG = "TouchOverlay";

    private final Context context;
    private final InputEventReader.TouchCallback callback;
    private View view;

    public TouchOverlay(Context context, InputEventReader.TouchCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    /** Must run on the main thread. Returns false if the overlay permission is missing. */
    @SuppressLint("ClickableViewAccessibility")
    public boolean start() {
        if (view != null) return true;
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No SYSTEM_ALERT_WINDOW permission, touches will not reset the idle timer");
            return false;
        }

        @SuppressWarnings("deprecation")
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        View v = new View(context);
        v.setOnTouchListener((ignored, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE
                    || event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                callback.onTouch();
            }
            return false;
        });

        try {
            ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).addView(v, params);
            view = v;
            Log.i(TAG, "Touch overlay active");
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not add touch overlay: " + e.getMessage());
            return false;
        }
    }

    public void stop() {
        if (view == null) return;
        try {
            ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).removeView(view);
        } catch (RuntimeException ignored) {
            // already gone
        }
        view = null;
    }
}
