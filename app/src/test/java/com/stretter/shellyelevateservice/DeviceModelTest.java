package com.stretter.shellyelevateservice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceModelTest {

    @Test
    public void detectsShellyModelsByBuildModel() {
        assertEquals(DeviceModel.STARGATE, DeviceModel.resolve("Stargate", null));
        assertEquals(DeviceModel.ATLANTIS, DeviceModel.resolve("Atlantis", null));
        assertEquals(DeviceModel.PEGASUS, DeviceModel.resolve("Pegasus", null));
        assertEquals(DeviceModel.BLAKE, DeviceModel.resolve("Blake", null));
    }

    @Test
    public void detectsNsPanelPro() {
        assertEquals(DeviceModel.NSPANEL_PRO, DeviceModel.resolve("px30_evb", null));
    }

    @Test
    public void unknownModelFallsBackToStargate() {
        assertEquals(DeviceModel.STARGATE, DeviceModel.resolve("Pixel 7", null));
        assertEquals(DeviceModel.STARGATE, DeviceModel.resolve(null, null));
        assertEquals(DeviceModel.STARGATE, DeviceModel.resolve("PX30_EVB", null)); // exact match only
    }

    @Test
    public void overrideWinsWhenValid() {
        assertEquals(DeviceModel.NSPANEL_PRO, DeviceModel.resolve("Blake", "NSPANEL_PRO"));
        assertEquals(DeviceModel.NSPANEL_PRO, DeviceModel.resolve("Blake", " nspanel_pro "));
        assertEquals(DeviceModel.BLAKE, DeviceModel.resolve("px30_evb", "BLAKE"));
    }

    @Test
    public void autoOrInvalidOverrideDetects() {
        assertEquals(DeviceModel.BLAKE, DeviceModel.resolve("Blake", ""));
        assertEquals(DeviceModel.BLAKE, DeviceModel.resolve("Blake", "auto"));
        assertEquals(DeviceModel.NSPANEL_PRO, DeviceModel.resolve("px30_evb", "nonsense"));
    }

    @Test
    public void nsPanelCapabilities() {
        DeviceModel m = DeviceModel.NSPANEL_PRO;
        assertEquals(0, m.inputs);
        assertEquals(0, m.buttons);
        assertFalse(m.hasTempHumSensor);
        assertTrue(m.hasProximitySensor);
        assertTrue(m.hasLightSensor);
        assertTrue(m.presenceFromProximity);
        assertTrue(m.screenOffViaTimeout);
        assertTrue(m.backlightRootOnly);
        assertEquals("/sys/class/backlight/backlight/brightness", m.backlightPath);
        assertEquals(0, m.defaultScreenSaverMinBrightness);
        assertEquals(480, m.screenWidthDp);
        assertEquals(480, m.screenHeightDp);
        assertEquals("Sonoff", m.manufacturer);
    }

    @Test
    public void shellyProfilesUnchanged() {
        for (DeviceModel m : DeviceModel.values()) {
            if (m == DeviceModel.NSPANEL_PRO) continue;
            assertEquals("Shelly", m.manufacturer);
            assertEquals("Shelly Wall Display", m.deviceName);
            assertTrue(m.hasTempHumSensor);
            assertTrue(m.hasLightSensor);
            assertEquals(1, m.inputs);
            assertNull(m.backlightPath);
            assertFalse(m.backlightRootOnly);
            assertFalse(m.presenceFromProximity);
            assertFalse(m.screenOffViaTimeout);
            assertEquals(10, m.defaultScreenSaverMinBrightness);
        }
        assertEquals(4, DeviceModel.BLAKE.buttons);
        assertTrue(DeviceModel.BLAKE.hasProximitySensor);
        assertFalse(DeviceModel.STARGATE.hasProximitySensor);
        assertEquals(-1.2d, DeviceModel.BLAKE.temperatureOffset, 0.0);
        assertEquals(10.0d, DeviceModel.BLAKE.humidityOffset, 0.0);
        assertEquals("SAWD-3A1XE10EU2", DeviceModel.BLAKE.modelName);
    }
}
