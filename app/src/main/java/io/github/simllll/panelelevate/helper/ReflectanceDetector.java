package io.github.simllll.panelelevate.helper;

/**
 * Near/far detection for a raw infrared reflectance proximity sensor (NSPanel Pro): higher values
 * mean closer, but the resting level drifts with ambient light (about 20 at night, 30-40 in
 * daylight), so no fixed threshold works all day.
 * <p>
 * The detector learns the resting level while nothing is near: it follows a rising level slowly
 * (daylight, {@link #RISE_TAU_MS}) and a falling level quickly ({@link #FALL_TAU_MS}). Near is a
 * value of at least {@code baseline + delta}; it stays near until the value drops to
 * {@code baseline + delta / 2} (hysteresis). While near, the baseline is frozen, so a person
 * standing in front of the panel is not learned as the new resting level.
 */
public class ReflectanceDetector {
    static final long RISE_TAU_MS = 60_000;
    static final long FALL_TAU_MS = 5_000;
    private static final long MAX_STEP_MS = 10_000;

    private float baseline = Float.NaN;
    private long lastMs;
    private boolean near;

    /** Feeds one sensor value; returns whether something is near. */
    public synchronized boolean feed(float value, long nowMs, int delta) {
        if (Float.isNaN(baseline)) {
            baseline = value;
            lastMs = nowMs;
        }
        long dt = Math.max(0, Math.min(MAX_STEP_MS, nowMs - lastMs));
        lastMs = nowMs;
        if (!near) {
            long tau = value > baseline ? RISE_TAU_MS : FALL_TAU_MS;
            float alpha = (float) (1 - Math.exp(-(double) dt / tau));
            baseline += (value - baseline) * alpha;
        }
        near = value >= baseline + delta || (near && value > baseline + delta / 2f);
        return near;
    }

    public synchronized float baseline() {
        return baseline;
    }
}
