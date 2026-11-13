package io.github.simllll.panelelevate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InputEventReaderTest {

    @Test
    public void eventSizeFollowsProcessBitness() {
        assertEquals(24, InputEventReader.eventSize(true));
        assertEquals(16, InputEventReader.eventSize(false));
    }

    @Test
    public void devicesWithKeysAreRead() {
        // adc-keys / power key
        assertTrue(InputEventReader.hasTouchOrKeys("c000000000000 0", "0"));
        assertTrue(InputEventReader.hasTouchOrKeys("100000 0 0 0", null));
    }

    @Test
    public void multiTouchWithoutKeysIsRead() {
        // 64-bit words: ABS_MT_POSITION_X/Y (53/54) + ABS_MT_TRACKING_ID (57)
        assertTrue(InputEventReader.hasTouchOrKeys("0", "260000000000000"));
        // 32-bit words: bits 53, 54, 57 live in the second-to-last word (bits 21, 22, 25)
        assertTrue(InputEventReader.hasTouchOrKeys("0", "2600000 0"));
    }

    @Test
    public void sensorOnlyDevicesAreSkipped() {
        // proximity: ABS_DISTANCE (25)
        assertFalse(InputEventReader.hasTouchOrKeys("0", "2000000"));
        // lightsensor-level: ABS_TOOL_WIDTH (28) + ABS_MISC (40), 64 and 32 bit words
        assertFalse(InputEventReader.hasTouchOrKeys("0", "10010000000"));
        assertFalse(InputEventReader.hasTouchOrKeys("0", "100 10000000"));
        assertFalse(InputEventReader.hasTouchOrKeys("0", ""));
        assertFalse(InputEventReader.hasTouchOrKeys(null, null));
    }

    @Test
    public void multiTouchCapability() {
        assertTrue(InputEventReader.hasMultiTouch("260000000000000"));
        assertTrue(InputEventReader.hasMultiTouch("2600000 0"));
        assertFalse(InputEventReader.hasMultiTouch("2000000"));
        assertFalse(InputEventReader.hasMultiTouch(null));
    }
}
