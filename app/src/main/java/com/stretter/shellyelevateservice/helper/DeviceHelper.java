package com.stretter.shellyelevateservice.helper;

import static com.stretter.shellyelevateservice.ShellyElevateApplication.mApplicationContext;
import static com.stretter.shellyelevateservice.ShellyElevateApplication.mMQTTServer;

import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Objects;

import com.stretter.shellyelevateservice.DeviceModel;

public class DeviceHelper {

    private static final String[][] possibleRelayFiles = {
            {
                    "/sys/devices/platform/leds/green_enable",
                    "/sys/class/strelay/relay1"
            },
            {
                    "/sys/devices/platform/leds/red_enable",
                    "/sys/class/strelay/relay2"
            }
    };

    private static final String tempAndHumFile = "/sys/devices/platform/sht3x-user/sht3x_access";
    private static final String[] screenBrightnessFiles = {
            "/sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness",
            "/sys/devices/platform/sprd_backlight/backlight/sprd_backlight/brightness",
            "/sys/devices/platform/backlight/backlight/backlight/brightness"
            };
    private String screenBrightnessFile;
    private final DeviceModel device = DeviceModel.getReportedDevice();
    // Root-only backlight (NSPanel Pro): written through the persistent su shell
    private final RootShell rootShell;
    private boolean screenOn = true;
    private volatile int lastScreenBrightness = -1;

    private static final String TAG = "DeviceHelper";

    public DeviceHelper() {
        if (device.needsRootForBacklight) {
            rootShell = RootShell.get();
            rootShell.prepareAsync();
        } else {
            rootShell = null;
        }

        if (device.backlightPath != null) {
            screenBrightnessFile = device.backlightPath;
            Log.i(TAG, "Using brightness file of " + device.name() + ": " + screenBrightnessFile
                    + (device.needsRootForBacklight ? " (via root shell)" : ""));
            return;
        }

        for (String brightnessFile : screenBrightnessFiles) {
            File f = new File(brightnessFile);
            if (f.exists()) {
                screenBrightnessFile = brightnessFile;
                Log.i(TAG, "Found brightness file: " + brightnessFile + " (canWrite=" + f.canWrite() + ")");
            }
        }
        if (screenBrightnessFile == null) {
            Log.wtf(TAG, "no brightness file found");
            screenBrightnessFile = "";
        } else {
            Log.i(TAG, "Using brightness file: " + screenBrightnessFile);
        }
    }

    public void setScreenOn(boolean on) {
        screenOn = on;

        setScreenBrightnessInternal(screenOn ? Math.max(lastScreenBrightness, 0) : 0);
    }

    public boolean getScreenOn() {
        return screenOn;
    }

    public void setScreenBrightness(int brightness) {
        lastScreenBrightness = brightness;
        setScreenBrightnessInternal(brightness);
    }

    private void setScreenBrightnessInternal(int brightness){
        if (mMQTTServer != null) mMQTTServer.publishScreenBrightness(brightness);

        writeScreenBrightness(brightness);
    }

    private void writeScreenBrightness(int brightness) {
        brightness = Math.max(0, Math.min(brightness, 255));

        Log.d(TAG, "Set brightness to: " + brightness);

        if (rootShell != null) {
            // Only the sysfs node: Android's own brightness (Settings) would ramp the backlight
            // through the lights HAL and could overwrite a 0 ("sleep"). Without root the
            // Settings API is the only way left (Android clamps its minimum, so never fully dark).
            final int value = brightness;
            rootShell.setBacklight(screenBrightnessFile, value, () -> writeBrightnessSetting(value));
            return;
        }

        writeBrightnessSetting(brightness);

        // Also try sysfs as backup
        writeFileContent(screenBrightnessFile, String.valueOf(brightness));
    }

    private static void writeBrightnessSetting(int brightness) {
        // Try Android Settings API first
        if (Settings.System.canWrite(mApplicationContext)) {
            try {
                Settings.System.putInt(mApplicationContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(mApplicationContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness);
                Log.d(TAG, "Set brightness via Settings API: " + brightness);
            } catch (Exception e) {
                Log.e(TAG, "Failed to set brightness via Settings API: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "App cannot write system settings - brightness control may not work");
        }
    }

    /**
     * Reboots the device. Devices with a root shell reboot through it (an app uid cannot run
     * "reboot" itself), falling back to the plain command.
     */
    public void reboot() throws IOException {
        if (rootShell != null && !Boolean.FALSE.equals(rootShell.isAvailable())) {
            rootShell.reboot(DeviceHelper::execReboot);
            return;
        }
        Runtime.getRuntime().exec("reboot");
    }

    private static void execReboot() {
        try {
            Runtime.getRuntime().exec("reboot");
        } catch (IOException e) {
            Log.e(TAG, "Error rebooting:", e);
        }
    }

    /** Force-stops an app (root shell where available, "am force-stop" as app uid otherwise). */
    public void forceStopApp(String packageName) throws IOException {
        if (rootShell != null && !Boolean.FALSE.equals(rootShell.isAvailable())) {
            try {
                rootShell.forceStop(packageName, () -> execForceStop(packageName));
                return;
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage());
            }
        }
        Runtime.getRuntime().exec("am force-stop " + packageName);
    }

    private static void execForceStop(String packageName) {
        try {
            Runtime.getRuntime().exec("am force-stop " + packageName);
        } catch (IOException e) {
            Log.w(TAG, "Could not force stop app: " + e.getMessage());
        }
    }

    /** Root shell used for root-only hardware nodes, null on devices that do not need one. */
    public RootShell getRootShell() {
        return rootShell;
    }

    /** Last brightness applied via setScreenBrightness, -1 if none yet. */
    public int getLastScreenBrightness() {
        return lastScreenBrightness;
    }

    public int getScreenBrightness() {
        try {
            return Integer.parseInt(sanitizeString(readFileContent(screenBrightnessFile)));
        } catch (NumberFormatException e) {
            // no readable sysfs node: report what we last applied
            return Math.max(lastScreenBrightness, 0);
        }
    }

    public boolean getRelay(int num) {
        boolean relayState = false;

        // Safety check
        if (num < 0 || num >= possibleRelayFiles.length || device.inputs == 0) return false;

        for (String relayFile : possibleRelayFiles[num]) {
            // readFileContent returns null when the node does not exist on this model
            String content = readFileContent(relayFile);
            relayState |= content != null && content.contains("1");
        }

        return relayState;
    }


    public void setRelay(int num, boolean state) {
        // Safety check
        if (num < 0 || num >= possibleRelayFiles.length || device.inputs == 0) return;

        for (String relayFile : possibleRelayFiles[num]) {
            writeFileContent(relayFile, state ? "1" : "0");
        }

        if (mMQTTServer != null && mMQTTServer.shouldSend()) {
            mMQTTServer.publishRelay(num, state);
        }
    }

    public double getTemperature() {
        if (!device.hasTempHumSensor) return -999;
        try
        {
            var content = readFileContent(tempAndHumFile);
            if (content == null || content.isEmpty()) return -999;

            String[] tempSplit = content.split(":");
            double temp = (Double.parseDouble(tempSplit[1]) * 175.0 / 65535.0) - 45.0;

            temp += device.temperatureOffset;
            return Math.round(temp * 10.0) / 10.0;
        } catch (Exception e) {
            Log.d("TAG", "Error while reading temperature: " + e);
            return -999;
        }
    }

    public double getHumidity() {
        if (!device.hasTempHumSensor) return -999;
        try {
            var content = readFileContent(tempAndHumFile);
            if (content == null || content.isEmpty()) return -999;

            String[] humiditySplit = content.split(":");
            double humidity = Double.parseDouble(humiditySplit[0]) * 100.0 / 65535.0;

            humidity += device.humidityOffset;

            return Math.round(humidity);
        } catch (Exception e) {
            Log.d("TAG", "Error while reading humidity: " + e);
            return -999;
        }
    }

    public static boolean fileExists(String filePath) {
        try {
            File file = new File(filePath);
            return file.exists() && file.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    private static String readFileContent(String filePath) {
       if (!fileExists(filePath))
            return null;

        StringBuilder content = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error when reading file with path:" + filePath + ":" + Objects.requireNonNull(e.getMessage()));
        }
        return content.toString();
    }

    private static String sanitizeString(String input) {
        if (input == null) return "";
        return input.replaceAll("[^0-9]", ""); // keep only digits
    }

    private static void writeFileContent(String filePath, String content) {
        Log.d(TAG, "Writing '" + content + "' to " + filePath);
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
            Log.d(TAG, "Write successful to " + filePath);
        } catch (IOException e) {
            Log.e(TAG, "FAILED to write to " + filePath + ": " + e.getMessage());
        }
    }
}
