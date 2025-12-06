package com.stretter.shellyelevateservice;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks button press states to detect single, double, and long press events.
 *
 * Timing logic:
 * - Long press: fired after LONG_PRESS_THRESHOLD_MS while button is held
 * - Double click: second press within DOUBLE_CLICK_WINDOW_MS of first release
 * - Single click: fired after DOUBLE_CLICK_WINDOW_MS if no second press
 */
public class ButtonStateTracker {

    private static final String TAG = "ButtonStateTracker";

    // Timing constants (in milliseconds)
    private static final long LONG_PRESS_THRESHOLD_MS = 500;
    private static final long DOUBLE_CLICK_WINDOW_MS = 300;

    public enum EventType {
        SINGLE("single"),
        DOUBLE("double"),
        LONG("long");

        private final String value;

        EventType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public interface ButtonEventListener {
        void onButtonEvent(int buttonNumber, EventType eventType);
    }

    private static class ButtonState {
        long pressTime = 0;
        long lastReleaseTime = 0;
        boolean isPressed = false;
        boolean longPressFired = false;
        int clickCount = 0;
        Runnable longPressRunnable = null;
        Runnable singleClickRunnable = null;
    }

    private final Map<Integer, ButtonState> buttonStates = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ButtonEventListener listener;

    public ButtonStateTracker(ButtonEventListener listener) {
        this.listener = listener;
    }

    /**
     * Call this when a button is pressed or released.
     *
     * @param buttonNumber The button number (1-4)
     * @param pressed true if pressed (key down), false if released (key up)
     */
    public void onButtonEvent(int buttonNumber, boolean pressed) {
        ButtonState state = buttonStates.get(buttonNumber);
        if (state == null) {
            state = new ButtonState();
            buttonStates.put(buttonNumber, state);
        }

        if (pressed) {
            onButtonPressed(buttonNumber, state);
        } else {
            onButtonReleased(buttonNumber, state);
        }
    }

    private void onButtonPressed(int buttonNumber, ButtonState state) {
        state.isPressed = true;
        state.pressTime = System.currentTimeMillis();
        state.longPressFired = false;

        // Cancel any pending single-click timer (we might be getting a double-click)
        if (state.singleClickRunnable != null) {
            handler.removeCallbacks(state.singleClickRunnable);
            state.singleClickRunnable = null;
        }

        // Check if this is a potential double-click (second press within window)
        long timeSinceLastRelease = state.pressTime - state.lastReleaseTime;
        if (state.clickCount == 1 && timeSinceLastRelease < DOUBLE_CLICK_WINDOW_MS) {
            // This is the second click of a double-click
            state.clickCount = 2;
            Log.d(TAG, "Button " + buttonNumber + ": detected second press for double-click");
        } else {
            // Fresh click sequence
            state.clickCount = 1;
        }

        // Start long-press timer
        final ButtonState finalState = state;
        state.longPressRunnable = () -> {
            if (finalState.isPressed && !finalState.longPressFired) {
                finalState.longPressFired = true;
                finalState.clickCount = 0; // Reset click count, long press takes precedence
                Log.d(TAG, "Button " + buttonNumber + ": LONG press fired");
                listener.onButtonEvent(buttonNumber, EventType.LONG);
            }
        };
        handler.postDelayed(state.longPressRunnable, LONG_PRESS_THRESHOLD_MS);
    }

    private void onButtonReleased(int buttonNumber, ButtonState state) {
        state.isPressed = false;
        state.lastReleaseTime = System.currentTimeMillis();

        // Cancel long-press timer
        if (state.longPressRunnable != null) {
            handler.removeCallbacks(state.longPressRunnable);
            state.longPressRunnable = null;
        }

        // If long press was already fired, don't fire anything else
        if (state.longPressFired) {
            state.clickCount = 0;
            Log.d(TAG, "Button " + buttonNumber + ": release after long press, ignoring");
            return;
        }

        // Handle click counting
        if (state.clickCount == 2) {
            // Double-click complete
            state.clickCount = 0;
            Log.d(TAG, "Button " + buttonNumber + ": DOUBLE click fired");
            listener.onButtonEvent(buttonNumber, EventType.DOUBLE);
        } else if (state.clickCount == 1) {
            // Start single-click timer - wait to see if double-click is coming
            final ButtonState finalState = state;
            state.singleClickRunnable = () -> {
                if (finalState.clickCount == 1) {
                    finalState.clickCount = 0;
                    Log.d(TAG, "Button " + buttonNumber + ": SINGLE click fired");
                    listener.onButtonEvent(buttonNumber, EventType.SINGLE);
                }
            };
            handler.postDelayed(state.singleClickRunnable, DOUBLE_CLICK_WINDOW_MS);
        }
    }

    /**
     * Clean up any pending callbacks
     */
    public void destroy() {
        for (ButtonState state : buttonStates.values()) {
            if (state.longPressRunnable != null) {
                handler.removeCallbacks(state.longPressRunnable);
            }
            if (state.singleClickRunnable != null) {
                handler.removeCallbacks(state.singleClickRunnable);
            }
        }
        buttonStates.clear();
    }
}