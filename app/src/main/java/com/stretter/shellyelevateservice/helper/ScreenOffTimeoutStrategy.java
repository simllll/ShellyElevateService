package com.stretter.shellyelevateservice.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Screen off without root or DEVICE_POWER (only WRITE_SETTINGS and WAKE_LOCK):
 *
 * <ul>
 *   <li>off: lower Settings.System.SCREEN_OFF_TIMEOUT to {@link #OFF_TIMEOUT_MS}, so Android's
 *   own power manager turns the display off (backlight 0, touches on the dark screen are dropped
 *   by Android), and restore the original timeout as soon as ACTION_SCREEN_OFF arrives (or
 *   after {@link #RESTORE_DEADLINE_MS} at the latest). The original value is persisted first,
 *   so a crash in between cannot leave the panel with a 1 s timeout ({@link #recover()}).</li>
 *   <li>on: a short SCREEN_BRIGHT_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE.</li>
 * </ul>
 *
 * While the screen is off a partial wake lock keeps the CPU running (MQTT, sensors, touch reader).
 */
public class ScreenOffTimeoutStrategy implements ScreenPowerStrategy {
    private static final String TAG = "ScreenOffTimeout";

    static final int OFF_TIMEOUT_MS = 1000;
    static final long RESTORE_DEADLINE_MS = 15_000;
    private static final long WAKE_HOLD_MS = 1_000;
    private static final int DEFAULT_TIMEOUT_MS = 600_000;
    // Original SCREEN_OFF_TIMEOUT while a screenOff is pending
    static final String SP_SAVED_TIMEOUT = "savedScreenOffTimeout";

    private final Context context;
    private final SharedPreferences prefs;
    private final PowerManager powerManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PowerManager.WakeLock cpuLock;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            Log.i(TAG, "Screen is off, restoring timeout");
            restoreTimeout();
        }
    };

    private final Runnable restoreDeadline = () -> {
        Log.w(TAG, "Screen did not turn off in time, restoring timeout");
        restoreTimeout();
    };

    public ScreenOffTimeoutStrategy(Context context) {
        this.context = context.getApplicationContext();
        // Own file: internal state, must not show up in (or be set via) the settings API
        this.prefs = this.context.getSharedPreferences("screen_power", Context.MODE_PRIVATE);
        this.powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.cpuLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shellyelevate:screenoff");
        this.cpuLock.setReferenceCounted(false);
    }

    /**
     * The timeout to restore: the saved one while a screenOff is pending, otherwise the current
     * one, unless that is our own short timeout (then Android's default).
     */
    static int timeoutToRestore(int saved, int current) {
        if (saved > OFF_TIMEOUT_MS) return saved;
        return current > OFF_TIMEOUT_MS ? current : DEFAULT_TIMEOUT_MS;
    }

    @Override
    public String name() {
        return "screen-off timeout";
    }

    @Override
    public boolean isUsable() {
        return Settings.System.canWrite(context);
    }

    @Override
    public boolean isScreenOn() {
        return powerManager.isInteractive();
    }

    @Override
    public void screenOff() {
        if (!powerManager.isInteractive()) return;
        int current = getTimeout();
        // Keep an already saved original (screenOff called twice before the screen went off)
        if (current > OFF_TIMEOUT_MS && !prefs.contains(SP_SAVED_TIMEOUT)) {
            prefs.edit().putInt(SP_SAVED_TIMEOUT, current).commit();
        }
        if (!putTimeout(OFF_TIMEOUT_MS)) {
            prefs.edit().remove(SP_SAVED_TIMEOUT).commit();
            return;
        }
        Log.i(TAG, "Screen off requested (timeout " + current + " -> " + OFF_TIMEOUT_MS + " ms)");
        cpuLock.acquire();
        if (!receiverRegistered) {
            context.registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            receiverRegistered = true;
        }
        handler.removeCallbacks(restoreDeadline);
        handler.postDelayed(restoreDeadline, RESTORE_DEADLINE_MS);
    }

    @SuppressWarnings("deprecation") // SCREEN_BRIGHT_WAKE_LOCK is the only way for a normal app
    @Override
    public void screenOn() {
        restoreTimeout();
        if (cpuLock.isHeld()) cpuLock.release();
        if (powerManager.isInteractive()) return;
        Log.i(TAG, "Waking the screen");
        PowerManager.WakeLock wake = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "shellyelevate:wake");
        wake.acquire(WAKE_HOLD_MS);
    }

    @Override
    public void recover() {
        if (prefs.contains(SP_SAVED_TIMEOUT)) {
            Log.w(TAG, "Restoring screen-off timeout left over from an interrupted sleep");
            restoreTimeout();
        }
    }

    @Override
    public void release() {
        restoreTimeout();
        if (cpuLock.isHeld()) cpuLock.release();
    }

    private void restoreTimeout() {
        handler.removeCallbacks(restoreDeadline);
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(screenOffReceiver);
            } catch (IllegalArgumentException ignored) {
                // not registered
            }
            receiverRegistered = false;
        }
        int current = getTimeout();
        boolean pending = prefs.contains(SP_SAVED_TIMEOUT);
        if (!pending && current > OFF_TIMEOUT_MS) return;
        int restore = timeoutToRestore(prefs.getInt(SP_SAVED_TIMEOUT, -1), current);
        if (putTimeout(restore)) {
            Log.i(TAG, "Screen-off timeout restored to " + restore + " ms");
            prefs.edit().remove(SP_SAVED_TIMEOUT).commit();
        }
    }

    private int getTimeout() {
        return Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_TIMEOUT_MS);
    }

    private boolean putTimeout(int value) {
        try {
            return Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, value);
        } catch (Exception e) {
            Log.e(TAG, "Cannot write SCREEN_OFF_TIMEOUT: " + e.getMessage());
            return false;
        }
    }
}
