package io.github.simllll.panelelevate.helper;

/**
 * Switches the display really off and on (as opposed to dimming via brightness). Used for
 * "sleep" on devices where the lowest brightness is still visibly lit (NSPanel Pro).
 * All methods except {@link #isScreenOn()} must be called on the main thread.
 */
public interface ScreenPowerStrategy {
    String name();

    /** Whether the strategy can work (permissions granted). */
    boolean isUsable();

    /** True while the display is on (interactive). Thread-safe. */
    boolean isScreenOn();

    void screenOff();

    void screenOn();

    /** Undo leftovers of an interrupted screenOff (e.g. after a crash), called once at start. */
    void recover();

    void release();
}
