package io.github.simllll.panelelevate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager.RunningAppProcessInfo;

import org.junit.Test;

public class WatchdogTargetTest {
    private static final String OWN = "io.github.simllll.panelelevate";
    private static final String HA = "io.homeassistant.companion.android.minimal";

    @Test
    public void resolvesInternalDashboard() {
        assertTrue(WatchdogTarget.isInternalDashboard(OWN, OWN));
        assertTrue(WatchdogTarget.isInternalDashboard("dashboard", OWN));
        assertTrue(WatchdogTarget.isInternalDashboard(" Dashboard ", OWN));
        assertTrue(WatchdogTarget.isInternalDashboard(OWN + ":dashboard", OWN));
        assertFalse(WatchdogTarget.isInternalDashboard(HA, OWN));
        assertFalse(WatchdogTarget.isInternalDashboard(null, OWN));
        assertFalse(WatchdogTarget.isInternalDashboard("", OWN));
    }

    @Test
    public void processNames() {
        assertEquals(OWN + ":dashboard", WatchdogTarget.processNameFor("dashboard", OWN));
        assertEquals(OWN + ":dashboard", WatchdogTarget.processNameFor(OWN, OWN));
        assertEquals(HA, WatchdogTarget.processNameFor(HA, OWN));
        assertEquals(HA, WatchdogTarget.processNameFor(" " + HA + " ", OWN));
    }

    @Test
    public void frontImportance() {
        assertTrue(WatchdogTarget.isFrontImportance(RunningAppProcessInfo.IMPORTANCE_FOREGROUND, false));
        assertFalse(WatchdogTarget.isFrontImportance(RunningAppProcessInfo.IMPORTANCE_VISIBLE, false));
        assertTrue(WatchdogTarget.isFrontImportance(RunningAppProcessInfo.IMPORTANCE_VISIBLE, true));
        assertTrue(WatchdogTarget.isFrontImportance(RunningAppProcessInfo.IMPORTANCE_FOREGROUND, true));
        assertFalse(WatchdogTarget.isFrontImportance(RunningAppProcessInfo.IMPORTANCE_SERVICE, true));
    }

    @Test
    public void parsesWhitelistedSettings() {
        assertEquals("dashboard", WatchdogTarget.parseAdbSetting("watchdogPackage", " dashboard "));
        assertEquals(HA, WatchdogTarget.parseAdbSetting("watchdogPackage", HA));
        assertEquals(true, WatchdogTarget.parseAdbSetting("watchdogEnabled", "true"));
        assertEquals(false, WatchdogTarget.parseAdbSetting("watchdogEnabled", "OFF"));
        assertEquals(15, WatchdogTarget.parseAdbSetting("watchdogInterval", "15"));
    }

    @Test
    public void rejectsInvalidSettings() {
        assertRejected("httpApiToken", "x");
        assertRejected("mqttBroker", "evil.example");
        assertRejected(null, "x");
        assertRejected("watchdogPackage", "  ");
        assertRejected("watchdogPackage", null);
        assertRejected("watchdogEnabled", "yes please");
        assertRejected("watchdogInterval", "0");
        assertRejected("watchdogInterval", "1.5");
    }

    @Test
    public void effectiveInterval() {
        assertEquals(10, WatchdogTarget.effectiveInterval(0, 10));
        assertEquals(5, WatchdogTarget.effectiveInterval(5, 10));
    }

    private static void assertRejected(String key, String value) {
        try {
            WatchdogTarget.parseAdbSetting(key, value);
            fail("expected rejection of " + key + "=" + value);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
