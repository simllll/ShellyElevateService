package com.stretter.shellyelevateservice;

import static com.stretter.shellyelevateservice.Constants.SP_DEVICE_MODEL;

import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * Supported devices and what hardware they have. Everything device specific (which MQTT
 * entities exist, how the backlight is written, ...) is decided from these capability flags,
 * never from the device name.
 */
public enum DeviceModel {
    //V1
    STARGATE("Stargate", "Shelly Wall Display", "SAWD-0A1XX10EU1", false, -2.7d, 7.0d, 0, 1), // Old One
    ATLANTIS("Atlantis", "Shelly Wall Display 2", "SAWD-1A1XX10EU1", true, -1.1d, 3.0d, 0, 1), // New One
    PEGASUS("Pegasus",  "Shelly Wall Display X2", "SAWD-2A1XX10EU1", true, -2.6d, 8.0d, 0, 1),

    //V2
    BLAKE("Blake", "Shelly Wall Display XL","SAWD-3A1XE10EU2", true, -1.2d, 10.0d, 4, 1),
    MAVERICK("Maverick", "Shelly Wall Display U1", "SAWD-4A1XE10US0", true, 0d, 0.0d, 0, 1), // TODO: not yet avaiable
    JENNA("Jenna", "Shelly Wall Display X2i", "SAWD-5A1XX10EU0", true, 0d, 0.0d, 0, 1), // TODO: not yet avaiable
    CALLY("Jenna","Shelly Wall Display XLi", "SAWD-6A1XX10EU0", true, 0d, 0.0d, 4, 1), // TODO: not yet avaiable

    // Sonoff NSPanel Pro (86 type, EU). Generic Rockchip PX30 board name, rooted userdebug build.
    // No relays, no temperature/humidity sensor, no hardware buttons (adc-keys exist in the
    // kernel but are not wired on the 86 model; they are only published with debugKeys).
    // Proximity and light are regular Android sensors (their raw input nodes are not readable).
    // The backlight sysfs node is root-only and su is not setuid, so brightness goes through the
    // Settings API, whose 0 only reaches sysfs 10. Sleep with screenSaverMinBrightness 0 turns
    // the display really off via the screen-off-timeout trick (ScreenOffTimeoutStrategy); the
    // touchscreen node (event1) is world-readable, so touches still wake it.
    // TODO: the kernel exports GPIO 113/114/115 (purpose unknown, maybe relay board or LEDs).
    //  Never drive them until their function is known.
    NSPANEL_PRO("px30_evb", "NSPanel Pro", "NSPanel Pro 86", "Sonoff", "Sonoff NSPanel Pro",
            true, true, false, 0, 0,
            "/sys/class/backlight/backlight/brightness", true, true, true, 0, 480, 480),
    ;

    private final String model;
    public final String modelName;
    public final String friendlyName;
    /** Manufacturer and device name for the Home Assistant device registry. */
    public final String manufacturer;
    public final String deviceName;
    public final boolean hasProximitySensor;
    public final boolean hasLightSensor;
    public final boolean hasTempHumSensor;
    public final double temperatureOffset;
    public final double humidityOffset;
    public final int buttons;
    /** Number of relays. */
    public final int inputs;
    /** Backlight sysfs node, null = probe the known Shelly paths. */
    public final String backlightPath;
    /**
     * The backlight node (and /dev/input) is not app-writable/readable: brightness goes through
     * the Settings API unless a BacklightStrategy (e.g. a setuid su) is usable, and touches are
     * detected with an overlay (TouchOverlay).
     */
    public final boolean backlightRootOnly;
    /** Sleep at brightness 0 turns the display off via ScreenOffTimeoutStrategy. */
    public final boolean screenOffViaTimeout;
    /** Publish a presence (occupancy) binary sensor derived from the proximity sensor. */
    public final boolean presenceFromProximity;
    /** Default for screenSaverMinBrightness (brightness while dimmed / asleep). */
    public final int defaultScreenSaverMinBrightness;
    public final int screenWidthDp;
    public final int screenHeightDp;

    // Shelly Wall Displays
    DeviceModel(String model, String friendlyName, String modelName, boolean hasProximitySensor, double temperatureOffset, double humidityOffset, int buttons, int inputs) {
        this(model, friendlyName, modelName, "Shelly", "Shelly Wall Display",
                hasProximitySensor, true, true, buttons, inputs,
                null, false, false, false, 10, 0, 0, temperatureOffset, humidityOffset);
    }

    // Devices without a temperature/humidity sensor
    DeviceModel(String model, String friendlyName, String modelName, String manufacturer, String deviceName,
                boolean hasProximitySensor, boolean hasLightSensor, boolean hasTempHumSensor, int buttons, int inputs,
                String backlightPath, boolean backlightRootOnly, boolean screenOffViaTimeout, boolean presenceFromProximity,
                int defaultScreenSaverMinBrightness, int screenWidthDp, int screenHeightDp) {
        this(model, friendlyName, modelName, manufacturer, deviceName, hasProximitySensor, hasLightSensor,
                hasTempHumSensor, buttons, inputs, backlightPath, backlightRootOnly, screenOffViaTimeout, presenceFromProximity,
                defaultScreenSaverMinBrightness, screenWidthDp, screenHeightDp, 0d, 0d);
    }

    DeviceModel(String model, String friendlyName, String modelName, String manufacturer, String deviceName,
                boolean hasProximitySensor, boolean hasLightSensor, boolean hasTempHumSensor, int buttons, int inputs,
                String backlightPath, boolean backlightRootOnly, boolean screenOffViaTimeout, boolean presenceFromProximity,
                int defaultScreenSaverMinBrightness, int screenWidthDp, int screenHeightDp,
                double temperatureOffset, double humidityOffset) {
        this.model = model;
        this.modelName = modelName;
        this.friendlyName = friendlyName;
        this.manufacturer = manufacturer;
        this.deviceName = deviceName;
        this.hasProximitySensor = hasProximitySensor;
        this.hasLightSensor = hasLightSensor;
        this.hasTempHumSensor = hasTempHumSensor;
        this.temperatureOffset = temperatureOffset;
        this.humidityOffset = humidityOffset;
        this.buttons = buttons;
        this.inputs = inputs;
        this.backlightPath = backlightPath;
        this.backlightRootOnly = backlightRootOnly;
        this.screenOffViaTimeout = screenOffViaTimeout;
        this.presenceFromProximity = presenceFromProximity;
        this.defaultScreenSaverMinBrightness = defaultScreenSaverMinBrightness;
        this.screenWidthDp = screenWidthDp;
        this.screenHeightDp = screenHeightDp;
    }

    public static DeviceModel getReportedDevice(){
        SharedPreferences prefs = ShellyElevateApplication.mSharedPreferences;
        String override = prefs == null ? null : prefs.getString(SP_DEVICE_MODEL, null);
        return resolve(Build.MODEL, override);
    }

    /**
     * Picks the device profile. A valid override (enum name, e.g. "NSPANEL_PRO", case-insensitive)
     * wins; "auto", empty or unknown values fall back to detection by Build.MODEL. Unknown models
     * are treated as the original Shelly Wall Display, as before.
     */
    static DeviceModel resolve(String buildModel, String override) {
        if (override != null) {
            String name = override.trim();
            for (DeviceModel deviceModel : values()) {
                if (deviceModel.name().equalsIgnoreCase(name)) return deviceModel;
            }
        }
        return Arrays.stream(DeviceModel.values()).filter(deviceType -> deviceType.model.equals(buildModel)).findFirst().orElse(DeviceModel.STARGATE);
    }

    @NonNull
    @Override
    public String toString() {
        //We are using this with the adapter in SettingsActivity
        return modelName;
    }
}
