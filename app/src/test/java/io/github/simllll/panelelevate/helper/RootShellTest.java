package io.github.simllll.panelelevate.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class RootShellTest {
    private static final String BACKLIGHT = "/sys/class/backlight/backlight/brightness";

    @Test
    public void backlightCommandClamps() {
        assertEquals("echo 128 > " + BACKLIGHT, RootShell.backlightCommand(BACKLIGHT, 128));
        assertEquals("echo 0 > " + BACKLIGHT, RootShell.backlightCommand(BACKLIGHT, -5));
        assertEquals("echo 255 > " + BACKLIGHT, RootShell.backlightCommand(BACKLIGHT, 999));
    }

    @Test
    public void rejectsUnsafePaths() {
        String[] bad = {null, "", "relative/path", "/sys/x; reboot", "/sys/x && rm -rf /", "/sys/$(id)",
                "/sys/x > /data/y", "/sys/../data/x", "/sys/x\nreboot", "/sys/a b"};
        for (String path : bad) {
            assertFalse(path, RootShell.isSafePath(path));
            try {
                RootShell.backlightCommand(path, 1);
                fail("accepted " + path);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        assertTrue(RootShell.isSafePath("/dev/input/event1"));
    }

    @Test
    public void catCommandExecsCat() {
        assertEquals("exec cat /dev/input/event1", RootShell.catCommand("/dev/input/event1"));
    }

    @Test
    public void forceStopValidatesPackage() {
        assertEquals("am force-stop io.homeassistant.companion.android.minimal",
                RootShell.forceStopCommand("io.homeassistant.companion.android.minimal"));
        for (String pkg : new String[]{null, "", "nodots", "a.b; reboot", "a.b c", "-a.b"}) {
            try {
                RootShell.forceStopCommand(pkg);
                fail("accepted " + pkg);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void rebootCommand() {
        assertEquals("reboot", RootShell.rebootCommand());
    }

    @Test
    public void onlySetuidRootSuIsUsable() {
        assertTrue(RootShell.isSetuidRoot(0104755, 0));   // -rwsr-xr-x root
        assertFalse(RootShell.isSetuidRoot(0100755, 0));  // -rwxr-xr-x root (NSPanel Pro)
        assertFalse(RootShell.isSetuidRoot(0104755, 2000)); // setuid, but not root-owned
    }

    @Test
    public void findsFirstExistingSu() {
        assertEquals("/system/xbin/su", RootShell.findSu(p -> true));
        assertEquals("/sbin/su", RootShell.findSu(p -> p.equals("/sbin/su") || p.equals("/su/bin/su")));
        assertNull(RootShell.findSu(p -> false));
    }
}
