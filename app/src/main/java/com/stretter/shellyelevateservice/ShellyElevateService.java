package com.stretter.shellyelevateservice;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import com.stretter.shellyelevateservice.helper.DeviceHelper;
import com.stretter.shellyelevateservice.helper.DeviceSensorManager;
import com.stretter.shellyelevateservice.helper.MediaHelper;
import com.stretter.shellyelevateservice.helper.ScreenOffTimeoutStrategy;
import com.stretter.shellyelevateservice.helper.ScreenPowerStrategy;
import com.stretter.shellyelevateservice.helper.ServiceHelper;
import com.stretter.shellyelevateservice.dashboard.popup.ButtonFeedbackContract;
import com.stretter.shellyelevateservice.mqtt.MQTTServer;

import static com.stretter.shellyelevateservice.Constants.*;
import static com.stretter.shellyelevateservice.Constants.SP_DEBUG_KEYS;

/**
 * Background service that provides:
 * - MQTT connectivity for Home Assistant
 * - HTTP API for configuration and control
 * - Hardware sensor reading (lux, proximity, temp, humidity)
 * - Screen dimming based on idle timeout
 * - Hardware button event handling
 *
 * This service can run independently without MainActivity (lite mode).
 */
public class ShellyElevateService extends Service {

    private static final String TAG = "ShellyElevateService";
    private static final String CHANNEL_ID = "shelly_elevate_channel";
    private static final int NOTIFICATION_ID = 1;

    // Core components
    private final DeviceModel device = DeviceModel.getReportedDevice();
    private SharedPreferences sharedPreferences;
    private DeviceHelper deviceHelper;
    private DeviceSensorManager sensorManager;
    private MQTTServer mqttServer;
    private HttpServer httpServer;
    private MediaHelper mediaHelper;
    private InputEventReader inputEventReader;
    private TouchOverlay touchOverlay;
    // Real display off/on for sleep at brightness 0 (NSPanel Pro), null elsewhere
    private ScreenPowerStrategy screenPower;
    private ButtonStateTracker buttonStateTracker;

    // Idle/dim management
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile long lastInteractionTime = System.currentTimeMillis();
    private volatile boolean isDimmed = false;
    private volatile boolean keepAliveFlag = false;

    // Auto-brightness only rewrites the brightness when it moves at least this many steps
    // (or reaches the min/max end of the range); avoids a Settings + sysfs + MQTT write on
    // every tiny light-sensor fluctuation.
    private static final int AUTO_BRIGHTNESS_MIN_STEP = 3;

    // Wide-range presence sensor (Blake) reports through gpio_keys as key presses
    private static final int KEY_PRESENCE_NEAR = 63; // KEY_F5
    private static final int KEY_PRESENCE_FAR = 64;  // KEY_F6

    // HTTP server retry
    private int httpRetryDelaySeconds = 5;

    // Proximity receiver
    private final BroadcastReceiver proximityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (INTENT_PROXIMITY_UPDATED.equals(intent.getAction())) {
                float proximity = intent.getFloatExtra(INTENT_PROXIMITY_KEY, -1f);
                onProximityChanged(proximity);
            }
        }
    };

    // Light sensor receiver
    private final BroadcastReceiver lightReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (INTENT_LIGHT_UPDATED.equals(intent.getAction())) {
                float lux = intent.getFloatExtra(INTENT_LIGHT_KEY, 0f);
                onLightChanged(lux);
            }
        }
    };

    // Wake/sleep receiver (from HTTP API and MQTT)
    private final BroadcastReceiver wakeSleepReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.i(TAG, "wakeSleepReceiver received: " + action);
            if (INTENT_SCREEN_SAVER_STARTED.equals(action)) {
                Log.i(TAG, "Calling dimScreen() from broadcast");
                dimScreen();
            } else if (INTENT_SCREEN_SAVER_STOPPED.equals(action)) {
                Log.i(TAG, "Calling wakeScreen() from broadcast");
                wakeScreen();
            }
        }
    };

    // Brightness control receiver (from MQTT)
    private final BroadcastReceiver brightnessReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (INTENT_SET_BRIGHTNESS.equals(intent.getAction())) {
                setManualBrightness(intent.getIntExtra(INTENT_BRIGHTNESS_KEY, 255));
            } else if (INTENT_SET_AUTO_BRIGHTNESS.equals(intent.getAction())) {
                setAutoBrightness(intent.getBooleanExtra(INTENT_AUTO_BRIGHTNESS_KEY, true));
            }
        }
    };

    // Restart app receiver (from MQTT)
    private final BroadcastReceiver restartAppReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (INTENT_RESTART_APP.equals(intent.getAction())) {
                Log.i(TAG, "Restart app command received");
                restartWatchdogApp();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ShellyElevateService starting...");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        sharedPreferences = getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE);
        initializeDefaultSettings();

        initializeComponents();
        registerReceivers();
        startIdleChecker();
        startHttpServer();
        startAppWatchdog();

        Log.i(TAG, "ShellyElevateService started successfully");
    }

    private void initializeDefaultSettings() {
        SharedPreferences.Editor editor = sharedPreferences.edit();

        // MQTT
        if (!sharedPreferences.contains(SP_MQTT_ENABLED)) editor.putBoolean(SP_MQTT_ENABLED, false);
        if (!sharedPreferences.contains(SP_MQTT_BROKER)) editor.putString(SP_MQTT_BROKER, "");
        if (!sharedPreferences.contains(SP_MQTT_PORT)) editor.putInt(SP_MQTT_PORT, 1883);
        if (!sharedPreferences.contains(SP_MQTT_USERNAME)) editor.putString(SP_MQTT_USERNAME, "");
        if (!sharedPreferences.contains(SP_MQTT_PASSWORD)) editor.putString(SP_MQTT_PASSWORD, "");

        // Screen
        if (!sharedPreferences.contains(SP_AUTOMATIC_BRIGHTNESS)) editor.putBoolean(SP_AUTOMATIC_BRIGHTNESS, true);
        if (!sharedPreferences.contains(SP_BRIGHTNESS)) editor.putInt(SP_BRIGHTNESS, 255);
        if (!sharedPreferences.contains(SP_MIN_BRIGHTNESS)) editor.putInt(SP_MIN_BRIGHTNESS, 48);

        // Screen saver / dimming
        if (!sharedPreferences.contains(SP_SCREEN_SAVER_ENABLED)) editor.putBoolean(SP_SCREEN_SAVER_ENABLED, true);
        if (!sharedPreferences.contains(SP_SCREEN_SAVER_DELAY)) editor.putInt(SP_SCREEN_SAVER_DELAY, 45);
        if (!sharedPreferences.contains(SP_SCREEN_SAVER_MIN_BRIGHTNESS)) editor.putInt(SP_SCREEN_SAVER_MIN_BRIGHTNESS, device.defaultScreenSaverMinBrightness);
        if (!sharedPreferences.contains(SP_WAKE_ON_PROXIMITY)) editor.putBoolean(SP_WAKE_ON_PROXIMITY, false);
        if (device.proximityIsReflectance() && !sharedPreferences.contains(SP_PROXIMITY_THRESHOLD)) {
            editor.putInt(SP_PROXIMITY_THRESHOLD, DeviceModel.REFLECTANCE_NEAR_DEFAULT);
        }

        // Watchdog
        if (!sharedPreferences.contains(SP_WATCHDOG_ENABLED)) editor.putBoolean(SP_WATCHDOG_ENABLED, false);
        if (!sharedPreferences.contains(SP_WATCHDOG_PACKAGE)) editor.putString(SP_WATCHDOG_PACKAGE, "io.homeassistant.companion.android.minimal");
        if (!sharedPreferences.contains(SP_WATCHDOG_INTERVAL)) editor.putInt(SP_WATCHDOG_INTERVAL, 10);

        // Other
        if (!sharedPreferences.contains(SP_HTTP_SERVER_ENABLED)) editor.putBoolean(SP_HTTP_SERVER_ENABLED, true);
        if (!sharedPreferences.contains(SP_HTTP_API_TOKEN)) editor.putString(SP_HTTP_API_TOKEN, "");
        if (!sharedPreferences.contains(SP_MEDIA_ENABLED)) editor.putBoolean(SP_MEDIA_ENABLED, true);
        if (!sharedPreferences.contains(SP_DEBUG_KEYS)) editor.putBoolean(SP_DEBUG_KEYS, false);

        editor.apply();
        Log.i(TAG, "Default settings initialized");
    }

    private void initializeComponents() {
        // Components read each other through the static references in ShellyElevateApplication
        // (MQTTServer schedules sensor publishes right away, DeviceHelper publishes brightness via
        // mMQTTServer, ...). Assign every reference as soon as the component exists so nothing
        // constructed later can observe a null.
        ShellyElevateApplication.mSharedPreferences = sharedPreferences;
        ShellyElevateApplication.mApplicationContext = getApplicationContext();

        // Device helper for hardware access (relay, brightness, temp/humidity)
        deviceHelper = new DeviceHelper();
        ShellyElevateApplication.mDeviceHelper = deviceHelper;

        if (device.screenOffViaTimeout) {
            screenPower = new ScreenOffTimeoutStrategy(this);
            screenPower.recover();
        }

        // Sensor manager for lux and proximity
        sensorManager = new DeviceSensorManager(this);
        ShellyElevateApplication.mDeviceSensorManager = sensorManager;

        // Media helper for audio
        mediaHelper = new MediaHelper();
        ShellyElevateApplication.mMediaHelper = mediaHelper;

        // MQTT server
        mqttServer = new MQTTServer();
        ShellyElevateApplication.mMQTTServer = mqttServer;

        // HTTP server
        httpServer = new HttpServer();
        ShellyElevateApplication.mHttpServer = httpServer;

        // Button state tracker for single/double/long press detection
        buttonStateTracker = new ButtonStateTracker(this::onButtonAction);

        // Input event reader for hardware buttons and touches (root-only nodes through the
        // root shell on devices that have one)
        inputEventReader = new InputEventReader(this::onRawButtonEvent, this::onTouchEvent, deviceHelper.getRootShell());
        inputEventReader.start();

        // No readable touchscreen node: detect touches with an overlay instead
        if (device.backlightRootOnly && !inputEventReader.hasTouchReader()) {
            touchOverlay = new TouchOverlay(this, this::onTouchEvent);
            touchOverlay.start();
        }
    }

    private void registerReceivers() {
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(proximityReceiver, new IntentFilter(INTENT_PROXIMITY_UPDATED));
        lbm.registerReceiver(lightReceiver, new IntentFilter(INTENT_LIGHT_UPDATED));

        // Wake/sleep from HTTP API
        IntentFilter wakeSleepFilter = new IntentFilter();
        wakeSleepFilter.addAction(INTENT_SCREEN_SAVER_STARTED);
        wakeSleepFilter.addAction(INTENT_SCREEN_SAVER_STOPPED);
        lbm.registerReceiver(wakeSleepReceiver, wakeSleepFilter);

        // Restart app from MQTT
        lbm.registerReceiver(restartAppReceiver, new IntentFilter(INTENT_RESTART_APP));
        lbm.registerReceiver(watchdogSettingsReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));

        // Brightness control from MQTT
        IntentFilter brightnessFilter = new IntentFilter();
        brightnessFilter.addAction(INTENT_SET_BRIGHTNESS);
        brightnessFilter.addAction(INTENT_SET_AUTO_BRIGHTNESS);
        lbm.registerReceiver(brightnessReceiver, brightnessFilter);
    }

    private void startIdleChecker() {
        scheduler.scheduleWithFixedDelay(guarded("Idle checker", () -> {
            if (keepAliveFlag) return;

            boolean dimEnabled = sharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true);
            if (!dimEnabled) return;

            int delay = sharedPreferences.getInt(SP_SCREEN_SAVER_DELAY, 45);
            long idleTime = System.currentTimeMillis() - lastInteractionTime;

            if (idleTime > delay * 1000L && !isDimmed) {
                dimScreen();
            }
        }), 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Wraps a periodic task so an exception is logged instead of silently cancelling
     * all future runs of a scheduleWithFixedDelay task.
     */
    private static Runnable guarded(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                Log.e(TAG, name + " failed, will retry on next run", t);
            }
        };
    }

    private void startHttpServer() {
        if (!sharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)) {
            Log.i(TAG, "HTTP server disabled in settings");
            return;
        }

        tryStartHttpServer();

        // Watchdog to restart if it dies
        scheduler.scheduleWithFixedDelay(guarded("HTTP server watchdog", () -> {
            if (sharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)) {
                if (httpServer == null || !httpServer.isAlive()) {
                    Log.w(TAG, "HTTP server not alive, restarting...");
                    tryStartHttpServer();
                }
            }
        }), 10, 10, TimeUnit.SECONDS);
    }

    private void tryStartHttpServer() {
        try {
            if (httpServer == null) {
                httpServer = new HttpServer();
                ShellyElevateApplication.mHttpServer = httpServer;
            }
            httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.i(TAG, "HTTP server started on port 8080");
            httpRetryDelaySeconds = 5;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server, retrying in " + httpRetryDelaySeconds + "s", e);
            int delay = httpRetryDelaySeconds;
            httpRetryDelaySeconds = Math.min(httpRetryDelaySeconds * 2, 60);
            scheduler.schedule(this::tryStartHttpServer, delay, TimeUnit.SECONDS);
        }
    }

    // === App Watchdog ===

    private static final String DEFAULT_WATCHDOG_PACKAGE = "io.homeassistant.companion.android.minimal";
    private static final int DEFAULT_WATCHDOG_INTERVAL = 10; // seconds

    private void startAppWatchdog() {
        boolean enabled = sharedPreferences.getBoolean(SP_WATCHDOG_ENABLED, false);
        Log.i(TAG, "startAppWatchdog() called, enabled=" + enabled);

        String packageName = sharedPreferences.getString(SP_WATCHDOG_PACKAGE, DEFAULT_WATCHDOG_PACKAGE);

        // The periodic check always runs and reads package, enabled flag and interval on every run,
        // so switching the target (HA app <-> dashboard) via HTTP/MQTT/adb takes effect without a
        // service restart. A settings change also triggers an immediate check.
        Log.i(TAG, "Starting app watchdog for package: " + packageName + " (interval: " + watchdogInterval() + "s)");

        if (enabled) {
            // Launch the app immediately on startup
            Log.i(TAG, "Watchdog: Initial launch of " + packageName);
            launchApp(packageName);
        } else {
            Log.i(TAG, "App watchdog disabled in settings (checks will start once enabled)");
        }

        scheduleWatchdogTick(watchdogInterval());
    }

    private int watchdogInterval() {
        return WatchdogTarget.effectiveInterval(
                sharedPreferences.getInt(SP_WATCHDOG_INTERVAL, DEFAULT_WATCHDOG_INTERVAL), DEFAULT_WATCHDOG_INTERVAL);
    }

    private final Runnable watchdogCheck = guarded("App watchdog", () -> {
        if (!sharedPreferences.getBoolean(SP_WATCHDOG_ENABLED, false)) {
            Log.d(TAG, "Watchdog: disabled, skipping check");
            return;
        }

        if (screenPower != null && !screenPower.isScreenOn()) {
            // Display off: every activity is stopped, relaunching would only churn
            Log.d(TAG, "Watchdog: screen off, skipping check");
            return;
        }

        String pkg = sharedPreferences.getString(SP_WATCHDOG_PACKAGE, DEFAULT_WATCHDOG_PACKAGE);
        boolean inForeground = isAppInForeground(pkg);
        Log.d(TAG, "Watchdog: checking " + pkg + ", inForeground=" + inForeground);

        if (!inForeground) {
            Log.w(TAG, "Watchdog: " + pkg + " is not in foreground, restarting...");
            launchApp(pkg);
        }
    });

    // Re-schedules itself with the current interval, so interval changes apply on the next run
    private final Runnable watchdogTick = () -> {
        watchdogCheck.run();
        scheduleWatchdogTick(watchdogInterval());
    };

    private void scheduleWatchdogTick(int delaySeconds) {
        try {
            scheduler.schedule(watchdogTick, delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            Log.d(TAG, "Watchdog: scheduler shut down, stopping checks");
        }
    }

    private final BroadcastReceiver watchdogSettingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                scheduler.execute(watchdogCheck);
            } catch (RejectedExecutionException ignored) {
                // service is shutting down
            }
        }
    };

    private boolean isAppInForeground(String packageName) {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return false;

        String processName = WatchdogTarget.processNameFor(packageName, getPackageName());
        boolean internal = WatchdogTarget.isInternalDashboard(packageName, getPackageName());

        // When screen is off, apps won't be "foreground" - check if running at all
        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) return false;

        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.processName.equals(processName) && (!internal || process.uid == android.os.Process.myUid())) {
                // If screen is off (dimmed), "visible" or better is OK; if on, require foreground.
                // The dashboard process hosts only DashboardActivity (+ its config receiver), so
                // foreground importance means the dashboard is the resumed activity.
                return WatchdogTarget.isFrontImportance(process.importance, isDimmed);
            }
        }
        return false;
    }

    private void launchApp(String packageName) {
        Log.i(TAG, "launchApp() called for: " + packageName);
        try {
            Intent launchIntent = ServiceHelper.watchdogLaunchIntent(this, packageName);
            Log.i(TAG, "launchIntent=" + launchIntent);
            if (launchIntent != null) {
                startActivity(launchIntent);
                Log.i(TAG, "Launched app successfully: " + packageName);
            } else {
                Log.e(TAG, "App not installed (no launch intent): " + packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch app: " + packageName, e);
        }
    }

    /**
     * Force kills and restarts the watchdog app.
     * Called via MQTT restart_app command.
     * For our own dashboard only the :dashboard process is killed: "am force-stop" on our own
     * package would also kill this service.
     */
    private void restartWatchdogApp() {
        String packageName = sharedPreferences.getString(SP_WATCHDOG_PACKAGE, DEFAULT_WATCHDOG_PACKAGE);
        Log.i(TAG, "Restarting watchdog app: " + packageName);

        if (WatchdogTarget.isInternalDashboard(packageName, getPackageName())) {
            killDashboardProcess();
        } else {
            // Force stop the app using am force-stop command
            try {
                deviceHelper.forceStopApp(packageName);
                Log.i(TAG, "Force stopped app: " + packageName);
            } catch (IOException e) {
                Log.w(TAG, "Could not force stop app: " + e.getMessage());
            }
        }

        // Wait a moment then relaunch
        mainHandler.postDelayed(() -> {
            Log.i(TAG, "Relaunching app: " + packageName);
            launchApp(packageName);
        }, 500);
    }

    private void killDashboardProcess() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes =
                activityManager == null ? null : activityManager.getRunningAppProcesses();
        if (processes == null) return;

        String processName = WatchdogTarget.dashboardProcessName(getPackageName());
        int myUid = android.os.Process.myUid();
        int myPid = android.os.Process.myPid();
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.processName.equals(processName) && process.uid == myUid && process.pid != myPid) {
                Log.i(TAG, "Killing dashboard process pid=" + process.pid);
                android.os.Process.killProcess(process.pid);
                return;
            }
        }
        Log.i(TAG, "Dashboard process not running, just launching it");
    }

    // === Screen Dim/Wake ===

    private void dimScreen() {
        Log.i(TAG, "dimScreen() called, isDimmed=" + isDimmed);
        if (isDimmed) {
            Log.i(TAG, "Already dimmed, skipping");
            return;
        }
        isDimmed = true;

        int minBrightness = sharedPreferences.getInt(SP_SCREEN_SAVER_MIN_BRIGHTNESS, device.defaultScreenSaverMinBrightness);
        if (minBrightness <= 0 && screenPower != null && screenPower.isUsable()) {
            // Lowest brightness is still lit here: switch the display off instead
            Log.i(TAG, "Switching screen off via " + screenPower.name());
            mainHandler.post(() -> {
                deviceHelper.markScreenOff();
                screenPower.screenOff();
            });
        } else {
            Log.i(TAG, "Dimming screen to brightness=" + minBrightness);
            mainHandler.post(() -> deviceHelper.setScreenBrightness(minBrightness));
        }

        // Publish state via MQTT (also remembered for re-publishing after a reconnect)
        if (mqttServer != null) {
            mqttServer.publishSleeping(true);
        }

        // Broadcast for any listeners
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STARTED));
    }

    private void wakeScreen() {
        if (!isDimmed) {
            // Display turned off by Android itself (its own timeout): wake requests still apply
            turnScreenOnIfOff();
            return;
        }
        isDimmed = false;
        lastInteractionTime = System.currentTimeMillis();
        turnScreenOnIfOff();

        int brightness = sharedPreferences.getInt(SP_BRIGHTNESS, 255);
        boolean autoBrightness = sharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true);

        if (autoBrightness) {
            float lux = sensorManager.getLastMeasuredLux();
            brightness = calculateBrightnessFromLux(lux);
        }

        final int finalBrightness = brightness;
        Log.i(TAG, "Waking screen to brightness=" + finalBrightness);
        mainHandler.post(() -> deviceHelper.setScreenBrightness(finalBrightness));

        // Publish state via MQTT (also remembered for re-publishing after a reconnect)
        if (mqttServer != null) {
            mqttServer.publishSleeping(false);
        }

        // Broadcast for any listeners
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STOPPED));
    }

    private boolean isScreenInteractive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private void turnScreenOnIfOff() {
        if (screenPower == null) return;
        // screenOn also restores the timeout if the display had not gone off yet
        mainHandler.post(() -> screenPower.screenOn());
    }

    public void keepAlive(boolean keepAlive) {
        this.keepAliveFlag = keepAlive;
        if (keepAlive && isDimmed) {
            wakeScreen();
        }
        if (!keepAlive) {
            lastInteractionTime = System.currentTimeMillis();
        }
    }

    private int calculateBrightnessFromLux(float lux) {
        int minBrightness = sharedPreferences.getInt(SP_MIN_BRIGHTNESS, 48);

        if (lux >= 500f) return 255;
        if (lux <= 30f) return minBrightness;

        double slope = (255.0 - minBrightness) / (500.0 - 30.0);
        double computed = minBrightness + slope * (lux - 30.0);
        return Math.max(0, Math.min(255, (int) Math.round(computed)));
    }

    // === Event Handlers ===

    private boolean lastProximityNear = false;
    private long lastProximityPublish = 0;

    private void onProximityChanged(float proximity) {
        boolean wakeOnProximity = sharedPreferences.getBoolean(SP_WAKE_ON_PROXIMITY, false);
        float maxRange = sensorManager.getMaxProximitySensorValue();

        // Only "near" counts as interaction; a "far" event (someone walking away) must not
        // reset the idle timer and postpone dimming
        boolean near;
        if (device.proximityIsReflectance()) {
            int threshold = DeviceModel.clampReflectanceThreshold(
                    sharedPreferences.getInt(SP_PROXIMITY_THRESHOLD, DeviceModel.REFLECTANCE_NEAR_DEFAULT));
            near = proximity >= threshold
                    || (lastProximityNear && proximity > DeviceModel.reflectanceFar(threshold));
        } else {
            near = proximity < maxRange - 0.5f;
        }
        boolean nearChanged = near != lastProximityNear;
        lastProximityNear = near;

        // Publish to MQTT. A reflectance sensor reports several values per second, so only
        // near/far changes go out immediately and other values at most every 5 s.
        long now = System.currentTimeMillis();
        boolean publish = !device.proximityIsReflectance() || nearChanged || now - lastProximityPublish >= 5000;
        if (publish && mqttServer != null && mqttServer.shouldSend()) {
            lastProximityPublish = now;
            mqttServer.publishProximity(proximity);
        }
        if (device.presenceFromProximity && mqttServer != null && !Boolean.valueOf(near).equals(mqttServer.getPresence())) {
            // No separate presence sensor: proximity near/far doubles as occupancy
            mqttServer.publishPresence(near);
        }
        if (near) {
            onSomethingNear(wakeOnProximity);
        }
    }

    /**
     * Presence sensor event (gpio_keys KEY_F5 = near, KEY_F6 = far).
     */
    private void onPresenceChanged(boolean near) {
        Log.d(TAG, "Presence: " + (near ? "near" : "far"));
        if (mqttServer != null) {
            mqttServer.publishPresence(near);
        }
        if (near) {
            onSomethingNear(sharedPreferences.getBoolean(SP_WAKE_ON_PROXIMITY, false));
        }
    }

    private void onSomethingNear(boolean wakeOnProximity) {
        lastInteractionTime = System.currentTimeMillis();
        // Also when the display is off without isDimmed (e.g. the service restarted while asleep)
        if (wakeOnProximity && (isDimmed || !isScreenInteractive())) {
            wakeScreen();
        }
    }

    private void onLightChanged(float lux) {
        if (isDimmed) return; // Don't adjust while dimmed

        boolean autoBrightness = sharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true);
        if (!autoBrightness) return;

        final int brightness = calculateBrightnessFromLux(lux);
        if (!shouldApplyAutoBrightness(deviceHelper.getLastScreenBrightness(), brightness)) return;
        mainHandler.post(() -> deviceHelper.setScreenBrightness(brightness));
    }

    private boolean shouldApplyAutoBrightness(int current, int target) {
        if (current < 0) return true; // nothing applied yet
        int diff = Math.abs(target - current);
        if (diff == 0) return false;
        if (diff >= AUTO_BRIGHTNESS_MIN_STEP) return true;
        // Small step, but still allow reaching the ends of the range exactly
        int minBrightness = sharedPreferences.getInt(SP_MIN_BRIGHTNESS, 48);
        return target == minBrightness || target == 255;
    }

    /**
     * MQTT brightness command: switches auto-brightness off so the value sticks and stores it
     * as the manual brightness. Applied right away when awake, otherwise on the next wake.
     */
    private void setManualBrightness(int brightness) {
        Log.i(TAG, "Manual brightness set to " + brightness);
        sharedPreferences.edit()
                .putInt(SP_BRIGHTNESS, brightness)
                .putBoolean(SP_AUTOMATIC_BRIGHTNESS, false)
                .apply();
        if (mqttServer != null) mqttServer.publishAutoBrightness();
        if (!isDimmed) {
            mainHandler.post(() -> deviceHelper.setScreenBrightness(brightness));
        }
    }

    private void setAutoBrightness(boolean enabled) {
        Log.i(TAG, "Automatic brightness " + (enabled ? "enabled" : "disabled"));
        sharedPreferences.edit().putBoolean(SP_AUTOMATIC_BRIGHTNESS, enabled).apply();
        if (mqttServer != null) mqttServer.publishAutoBrightness();
        if (!isDimmed) {
            final int brightness = enabled
                    ? calculateBrightnessFromLux(sensorManager.getLastMeasuredLux())
                    : sharedPreferences.getInt(SP_BRIGHTNESS, 255);
            mainHandler.post(() -> deviceHelper.setScreenBrightness(brightness));
        }
    }

    /**
     * Called by InputEventReader for raw key press/release events.
     * Routes known buttons to ButtonStateTracker for single/double/long detection.
     */
    private void onRawButtonEvent(int keyCode, boolean pressed) {
        // Log key events for debugging (controlled by debug flag)
        boolean debugKeys = sharedPreferences.getBoolean(SP_DEBUG_KEYS, false);
        if (debugKeys) {
            Log.i(TAG, "Raw button event: keyCode=" + keyCode + ", pressed=" + pressed);
        }

        // The presence sensor is not a button: it must not wake the screen unconditionally
        // (only with wakeOnProximity) and a "far" event must not count as interaction
        if (keyCode == KEY_PRESENCE_NEAR || keyCode == KEY_PRESENCE_FAR) {
            if (pressed) {
                onPresenceChanged(keyCode == KEY_PRESENCE_NEAR);
            }
            return;
        }

        // Any button press wakes the screen and resets idle timer
        if (pressed) {
            lastInteractionTime = System.currentTimeMillis();
            if (isDimmed) {
                wakeScreen();
            }
        }

        // Map hardware key codes 59-62 to button numbers 1-4
        int buttonNumber = keyCodeToButtonNumber(keyCode);
        if (buttonNumber > 0) {
            // Instant pill on the native dashboard; its label is refined by single/double/long
            if (pressed) {
                sendDashboardButtonFeedback(buttonNumber, ButtonFeedbackContract.EVENT_DOWN);
            }
            // Route to ButtonStateTracker for single/double/long detection
            buttonStateTracker.onButtonEvent(buttonNumber, pressed);
        } else if (debugKeys && mqttServer != null && mqttServer.shouldSend()) {
            // Publish unknown keys to MQTT only when debug mode is enabled
            Log.w(TAG, "Unknown key code: " + keyCode + ", publishing to MQTT");
            mqttServer.publishUnknownKey(keyCode, pressed);
        }
    }

    /**
     * Maps hardware key codes to button numbers.
     * @return button number (1-4) or 0 if unknown
     */
    private int keyCodeToButtonNumber(int keyCode) {
        switch (keyCode) {
            case 59: return 1;
            case 60: return 2;
            case 61: return 3;
            case 62: return 4;
            default: return 0;
        }
    }

    /**
     * Called by ButtonStateTracker when a button action is detected.
     * Publishes the event to MQTT and tells a running native dashboard to show instant feedback.
     */
    private void onButtonAction(int buttonNumber, ButtonStateTracker.EventType eventType) {
        Log.i(TAG, "Button " + buttonNumber + " action: " + eventType.getValue());
        if (mqttServer != null && mqttServer.shouldSend()) {
            mqttServer.publishButtonEvent(buttonNumber, eventType.getValue());
        }
        sendDashboardButtonFeedback(buttonNumber, eventType.getValue());
    }

    /**
     * Cross-process broadcast to the :dashboard process (LocalBroadcastManager stays in-process).
     * Restricted to our package; the dashboard registers a not-exported receiver only while it is
     * started, so without a running dashboard this is a no-op.
     */
    private void sendDashboardButtonFeedback(int buttonNumber, String event) {
        try {
            Intent intent = new Intent(ButtonFeedbackContract.ACTION)
                    .setPackage(getPackageName())
                    .putExtra(ButtonFeedbackContract.EXTRA_BUTTON, buttonNumber)
                    .putExtra(ButtonFeedbackContract.EXTRA_EVENT, event);
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not send button feedback to dashboard: " + e.getMessage());
        }
    }

    public void onTouchEvent() {
        lastInteractionTime = System.currentTimeMillis();
        if (isDimmed) {
            wakeScreen();
        } else if (screenPower != null && !screenPower.isScreenOn()) {
            turnScreenOnIfOff();
        }
    }

    // === Service Lifecycle ===

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ShellyElevateService stopping...");

        // Unregister receivers
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(proximityReceiver);
        lbm.unregisterReceiver(lightReceiver);
        lbm.unregisterReceiver(wakeSleepReceiver);
        lbm.unregisterReceiver(restartAppReceiver);
        lbm.unregisterReceiver(watchdogSettingsReceiver);
        lbm.unregisterReceiver(brightnessReceiver);

        // Stop input reader
        if (inputEventReader != null) {
            inputEventReader.stop();
        }
        if (touchOverlay != null) {
            touchOverlay.stop();
        }
        if (screenPower != null) {
            screenPower.release();
        }

        // Cleanup button state tracker
        if (buttonStateTracker != null) {
            buttonStateTracker.destroy();
        }

        // Shutdown scheduler
        scheduler.shutdownNow();

        // Cleanup components
        if (httpServer != null) {
            httpServer.onDestroy();
        }
        if (mqttServer != null) {
            mqttServer.onDestroy();
        }
        if (sensorManager != null) {
            sensorManager.onDestroy();
        }
        if (mediaHelper != null) {
            mediaHelper.onDestroy();
        }

        Log.i(TAG, "ShellyElevateService stopped");
        super.onDestroy();
    }

    // === Notification ===

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Shelly Display Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background service for Shelly Wall Display");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Shelly Display")
                .setContentText("Service running")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    // === Public Accessors ===

    public boolean isDimmed() {
        return isDimmed;
    }

    public DeviceHelper getDeviceHelper() {
        return deviceHelper;
    }

    public MQTTServer getMqttServer() {
        return mqttServer;
    }

    public DeviceSensorManager getSensorManager() {
        return sensorManager;
    }
}
