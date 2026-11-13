package io.github.simllll.panelelevate.helper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Values from the NSPanel Pro at the entrance (2026-11-13): night ~20, daylight 27-41, person 54-82. */
public class ReflectanceDetectorTest {
    private static final int DELTA = 20;

    /** Feeds [value] every 250 ms for [seconds]; returns whether it was near at any point. */
    private static boolean run(ReflectanceDetector d, long[] clock, float value, int seconds) {
        boolean any = false;
        for (int i = 0; i < seconds * 4; i++) {
            clock[0] += 250;
            any |= d.feed(value, clock[0], DELTA);
        }
        return any;
    }

    @Test
    public void daylightDriftIsNotPresence() {
        ReflectanceDetector d = new ReflectanceDetector();
        long[] t = {0};
        assertFalse(run(d, t, 20, 60));
        // Resting level climbs to ~40 over half an hour with noise, like at noon
        boolean any = false;
        for (int i = 0; i < 30 * 60 * 4; i++) {
            t[0] += 250;
            float level = 20 + 20f * i / (30 * 60 * 4) + ((i % 7) - 3);
            any |= d.feed(level, t[0], DELTA);
        }
        assertFalse(any);
    }

    @Test
    public void personInFrontIsDetectedAtNightAndInDaylight() {
        ReflectanceDetector night = new ReflectanceDetector();
        long[] t = {0};
        run(night, t, 20, 60);
        assertTrue(night.feed(54, t[0] += 250, DELTA));

        ReflectanceDetector day = new ReflectanceDetector();
        long[] u = {0};
        run(day, u, 37, 120);
        assertFalse(day.feed(41, u[0] += 250, DELTA));
        assertTrue(day.feed(62, u[0] += 250, DELTA));
    }

    @Test
    public void standingPersonIsNotLearnedAndLeavingIsFar() {
        ReflectanceDetector d = new ReflectanceDetector();
        long[] t = {0};
        run(d, t, 20, 60);
        assertTrue(run(d, t, 60, 300));          // five minutes in front of the panel
        assertTrue(d.feed(60, t[0] += 250, DELTA));
        assertFalse(d.feed(25, t[0] += 250, DELTA)); // gone again
    }
}
