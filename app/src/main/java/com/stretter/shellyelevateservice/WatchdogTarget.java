package com.stretter.shellyelevateservice;

import static com.stretter.shellyelevateservice.Constants.SP_WATCHDOG_ENABLED;
import static com.stretter.shellyelevateservice.Constants.SP_WATCHDOG_INTERVAL;
import static com.stretter.shellyelevateservice.Constants.SP_WATCHDOG_PACKAGE;

import android.app.ActivityManager;

/**
 * Pure watchdog logic (no Android calls, unit-tested): which app the watchdog keeps in front,
 * when that app counts as "in front" and which settings adb may change.
 *
 * The watchdog target is either an external package (e.g. the HA Companion app) or our own native
 * dashboard, selected by {@code watchdogPackage = <own package>} or the alias {@code dashboard}.
 * The dashboard runs in the process {@code <own package>:dashboard}, so it must never be
 * force-stopped by package name: that would also kill the background service.
 */
public final class WatchdogTarget {
    public static final String DASHBOARD_ALIAS = "dashboard";
    public static final String DASHBOARD_PROCESS_SUFFIX = ":dashboard";

    /** Minimum check interval; shorter values would relaunch apps faster than they can start. */
    static final int MIN_INTERVAL_SECONDS = 1;

    private WatchdogTarget() {
    }

    /** True if the watchdog setting selects our own native dashboard. */
    public static boolean isInternalDashboard(String watchdogPackage, String ownPackage) {
        if (watchdogPackage == null) return false;
        String pkg = watchdogPackage.trim();
        return pkg.equalsIgnoreCase(DASHBOARD_ALIAS) || pkg.equals(ownPackage)
                || pkg.equals(dashboardProcessName(ownPackage));
    }

    /** Name of the process the dashboard activity runs in. */
    public static String dashboardProcessName(String ownPackage) {
        return ownPackage + DASHBOARD_PROCESS_SUFFIX;
    }

    /** Name of the process to look for in the running processes for the given target. */
    public static String processNameFor(String watchdogPackage, String ownPackage) {
        return isInternalDashboard(watchdogPackage, ownPackage)
                ? dashboardProcessName(ownPackage)
                : watchdogPackage == null ? "" : watchdogPackage.trim();
    }

    /**
     * True if a process with this importance counts as "in front". While the screen is dimmed
     * the app may drop to visible; otherwise it has to be the foreground app.
     */
    public static boolean isFrontImportance(int importance, boolean dimmed) {
        if (dimmed) {
            return importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
        }
        return importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    /** Settings the DUMP-protected adb receiver may change. */
    public static boolean isAdbSettableKey(String key) {
        return SP_WATCHDOG_PACKAGE.equals(key) || SP_WATCHDOG_ENABLED.equals(key)
                || SP_WATCHDOG_INTERVAL.equals(key);
    }

    /**
     * Converts an adb string extra into the typed value for the given whitelisted key
     * (String / Boolean / Integer, matching the stored preference types).
     *
     * @throws IllegalArgumentException for unknown keys or invalid values
     */
    public static Object parseAdbSetting(String key, String value) {
        if (!isAdbSettableKey(key)) {
            throw new IllegalArgumentException("key not allowed: " + key);
        }
        if (value == null) {
            throw new IllegalArgumentException("missing value");
        }
        String v = value.trim();
        switch (key) {
            case SP_WATCHDOG_PACKAGE:
                if (v.isEmpty()) throw new IllegalArgumentException("package must not be empty");
                return v;
            case SP_WATCHDOG_ENABLED:
                if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on")) return true;
                if (v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("off")) return false;
                throw new IllegalArgumentException("expected true or false");
            case SP_WATCHDOG_INTERVAL:
                int interval;
                try {
                    interval = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("expected a whole number of seconds");
                }
                if (interval < MIN_INTERVAL_SECONDS) {
                    throw new IllegalArgumentException("interval must be >= " + MIN_INTERVAL_SECONDS);
                }
                return interval;
            default:
                throw new IllegalArgumentException("key not allowed: " + key);
        }
    }

    /** Watchdog interval to use for a stored value (falls back to the default for invalid values). */
    public static int effectiveInterval(int stored, int fallback) {
        return stored >= MIN_INTERVAL_SECONDS ? stored : fallback;
    }
}
