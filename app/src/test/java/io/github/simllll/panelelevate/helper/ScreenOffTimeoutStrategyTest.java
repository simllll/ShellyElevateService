package io.github.simllll.panelelevate.helper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ScreenOffTimeoutStrategyTest {

    @Test
    public void restoresSavedTimeout() {
        assertEquals(600_000, ScreenOffTimeoutStrategy.timeoutToRestore(600_000, ScreenOffTimeoutStrategy.OFF_TIMEOUT_MS));
        assertEquals(Integer.MAX_VALUE, ScreenOffTimeoutStrategy.timeoutToRestore(Integer.MAX_VALUE, 1000));
    }

    @Test
    public void withoutSavedValueKeepsARealCurrentTimeout() {
        assertEquals(300_000, ScreenOffTimeoutStrategy.timeoutToRestore(-1, 300_000));
    }

    @Test
    public void neverRestoresOurOwnShortTimeout() {
        // crash after lowering, saved value lost: fall back to Android's default
        assertEquals(600_000, ScreenOffTimeoutStrategy.timeoutToRestore(-1, ScreenOffTimeoutStrategy.OFF_TIMEOUT_MS));
        assertEquals(600_000, ScreenOffTimeoutStrategy.timeoutToRestore(500, 500));
    }
}
