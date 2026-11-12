package com.stretter.shellyelevateservice.helper;

/**
 * A way to write a backlight node the app cannot write itself (e.g. NSPanel Pro, where the
 * Settings API bottoms out at sysfs 10 and only a privileged writer reaches 0 = screen off).
 * DeviceHelper uses the first usable strategy and falls back to the Settings API otherwise.
 */
public interface BacklightStrategy {
    String name();

    /** Cheap check whether this strategy can work on this device at all. */
    boolean isUsable();

    /**
     * Writes the brightness (0..255) to the node, asynchronously. Runs the fallback if it turns
     * out not to work.
     */
    void setBacklight(String path, int brightness, Runnable fallback);
}
