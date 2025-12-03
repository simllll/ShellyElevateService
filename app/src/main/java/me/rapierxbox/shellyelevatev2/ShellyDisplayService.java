package me.rapierxbox.shellyelevatev2;

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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import me.rapierxbox.shellyelevatev2.helper.DeviceHelper;
import me.rapierxbox.shellyelevatev2.helper.DeviceSensorManager;
import me.rapierxbox.shellyelevatev2.helper.MediaHelper;
import me.rapierxbox.shellyelevatev2.mqtt.MQTTServer;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.Constants.SP_DEBUG_KEYS;

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
public class ShellyDisplayService extends Service {

    private static final String TAG = "ShellyDisplayService";
    private static final String CHANNEL_ID = "shelly_display_channel";
    private static final int NOTIFICATION_ID = 1;

    // Core components
    private SharedPreferences sharedPreferences;
    private DeviceHelper deviceHelper;
    private DeviceSensorManager sensorManager;
    private MQTTServer mqttServer;
    private HttpServer httpServer;
    private MediaHelper mediaHelper;
    private InputEventReader inputEventReader;

    // Idle/dim management
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile long lastInteractionTime = System.currentTimeMillis();
    private volatile boolean isDimmed = false;
    private volatile boolean keepAliveFlag = false;

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

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ShellyDisplayService starting...");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        sharedPreferences = getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE);
        initializeDefaultSettings();

        initializeComponents();
        registerReceivers();
        startIdleChecker();
        startHttpServer();
        startAppWatchdog();

        Log.i(TAG, "ShellyDisplayService started successfully");
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
        if (!sharedPreferences.contains(SP_SCREEN_SAVER_MIN_BRIGHTNESS)) editor.putInt(SP_SCREEN_SAVER_MIN_BRIGHTNESS, 10);
        if (!sharedPreferences.contains(SP_WAKE_ON_PROXIMITY)) editor.putBoolean(SP_WAKE_ON_PROXIMITY, false);

        // Watchdog
        if (!sharedPreferences.contains(SP_WATCHDOG_ENABLED)) editor.putBoolean(SP_WATCHDOG_ENABLED, false);
        if (!sharedPreferences.contains(SP_WATCHDOG_PACKAGE)) editor.putString(SP_WATCHDOG_PACKAGE, "io.homeassistant.companion.android");
        if (!sharedPreferences.contains(SP_WATCHDOG_INTERVAL)) editor.putInt(SP_WATCHDOG_INTERVAL, 10);

        // Other
        if (!sharedPreferences.contains(SP_HTTP_SERVER_ENABLED)) editor.putBoolean(SP_HTTP_SERVER_ENABLED, true);
        if (!sharedPreferences.contains(SP_MEDIA_ENABLED)) editor.putBoolean(SP_MEDIA_ENABLED, true);
        if (!sharedPreferences.contains(SP_DEBUG_KEYS)) editor.putBoolean(SP_DEBUG_KEYS, false);

        editor.apply();
        Log.i(TAG, "Default settings initialized");
    }

    private void initializeComponents() {
        // Device helper for hardware access (relay, brightness, temp/humidity)
        deviceHelper = new DeviceHelper();

        // Sensor manager for lux and proximity
        sensorManager = new DeviceSensorManager(this);

        // Media helper for audio
        mediaHelper = new MediaHelper();

        // MQTT server
        mqttServer = new MQTTServer();

        // HTTP server
        httpServer = new HttpServer();

        // Input event reader for hardware buttons
        inputEventReader = new InputEventReader(this::onButtonEvent);
        inputEventReader.start();

        // Store references for global access (needed by HttpServer, etc.)
        ShellyElevateApplication.mDeviceHelper = deviceHelper;
        ShellyElevateApplication.mDeviceSensorManager = sensorManager;
        ShellyElevateApplication.mMQTTServer = mqttServer;
        ShellyElevateApplication.mHttpServer = httpServer;
        ShellyElevateApplication.mMediaHelper = mediaHelper;
        ShellyElevateApplication.mSharedPreferences = sharedPreferences;
        ShellyElevateApplication.mApplicationContext = getApplicationContext();
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
    }

    private void startIdleChecker() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (keepAliveFlag) return;

            boolean dimEnabled = sharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true);
            if (!dimEnabled) return;

            int delay = sharedPreferences.getInt(SP_SCREEN_SAVER_DELAY, 45);
            long idleTime = System.currentTimeMillis() - lastInteractionTime;

            if (idleTime > delay * 1000L && !isDimmed) {
                dimScreen();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void startHttpServer() {
        if (!sharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)) {
            Log.i(TAG, "HTTP server disabled in settings");
            return;
        }

        tryStartHttpServer();

        // Watchdog to restart if it dies
        scheduler.scheduleWithFixedDelay(() -> {
            if (sharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)) {
                if (httpServer == null || !httpServer.isAlive()) {
                    Log.w(TAG, "HTTP server not alive, restarting...");
                    tryStartHttpServer();
                }
            }
        }, 10, 10, TimeUnit.SECONDS);
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

    private static final String DEFAULT_WATCHDOG_PACKAGE = "io.homeassistant.companion.android";
    private static final int DEFAULT_WATCHDOG_INTERVAL = 10; // seconds

    private void startAppWatchdog() {
        boolean enabled = sharedPreferences.getBoolean(SP_WATCHDOG_ENABLED, false);
        Log.i(TAG, "startAppWatchdog() called, enabled=" + enabled);

        if (!enabled) {
            Log.i(TAG, "App watchdog disabled in settings");
            return;
        }

        String packageName = sharedPreferences.getString(SP_WATCHDOG_PACKAGE, DEFAULT_WATCHDOG_PACKAGE);
        int interval = sharedPreferences.getInt(SP_WATCHDOG_INTERVAL, DEFAULT_WATCHDOG_INTERVAL);

        Log.i(TAG, "Starting app watchdog for package: " + packageName + " (interval: " + interval + "s)");

        // Launch the app immediately on startup
        Log.i(TAG, "Watchdog: Initial launch of " + packageName);
        launchApp(packageName);

        // Schedule periodic checks
        scheduler.scheduleWithFixedDelay(() -> {
            if (!sharedPreferences.getBoolean(SP_WATCHDOG_ENABLED, false)) {
                Log.d(TAG, "Watchdog: disabled, skipping check");
                return;
            }

            String pkg = sharedPreferences.getString(SP_WATCHDOG_PACKAGE, DEFAULT_WATCHDOG_PACKAGE);
            boolean inForeground = isAppInForeground(pkg);
            Log.d(TAG, "Watchdog: checking " + pkg + ", inForeground=" + inForeground);

            if (!inForeground) {
                Log.w(TAG, "Watchdog: " + pkg + " is not in foreground, restarting...");
                launchApp(pkg);
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    private boolean isAppInForeground(String packageName) {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return false;

        // When screen is off, apps won't be "foreground" - check if running at all
        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) return false;

        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.processName.equals(packageName)) {
                // If screen is off (dimmed), consider "visible" or better as OK
                if (isDimmed) {
                    return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                }
                // If screen is on, require foreground
                return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            }
        }
        return false;
    }

    private void launchApp(String packageName) {
        Log.i(TAG, "launchApp() called for: " + packageName);
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            Log.i(TAG, "launchIntent=" + launchIntent);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(launchIntent);
                Log.i(TAG, "Launched app successfully: " + packageName);
            } else {
                Log.e(TAG, "App not installed (no launch intent): " + packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch app: " + packageName, e);
        }
    }

    // === Screen Dim/Wake ===

    private void dimScreen() {
        Log.i(TAG, "dimScreen() called, isDimmed=" + isDimmed);
        if (isDimmed) {
            Log.i(TAG, "Already dimmed, skipping");
            return;
        }
        isDimmed = true;

        int minBrightness = sharedPreferences.getInt(SP_SCREEN_SAVER_MIN_BRIGHTNESS, 10);
        Log.i(TAG, "Dimming screen to brightness=" + minBrightness);
        mainHandler.post(() -> deviceHelper.setScreenBrightness(minBrightness));

        // Publish state via MQTT
        if (mqttServer != null && mqttServer.shouldSend()) {
            mqttServer.publishSleeping(true);
        }

        // Broadcast for any listeners
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STARTED));
    }

    private void wakeScreen() {
        if (!isDimmed) return;
        isDimmed = false;
        lastInteractionTime = System.currentTimeMillis();

        int brightness = sharedPreferences.getInt(SP_BRIGHTNESS, 255);
        boolean autoBrightness = sharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true);

        if (autoBrightness) {
            float lux = sensorManager.getLastMeasuredLux();
            brightness = calculateBrightnessFromLux(lux);
        }

        final int finalBrightness = brightness;
        Log.i(TAG, "Waking screen to brightness=" + finalBrightness);
        mainHandler.post(() -> deviceHelper.setScreenBrightness(finalBrightness));

        // Publish state via MQTT
        if (mqttServer != null && mqttServer.shouldSend()) {
            mqttServer.publishSleeping(false);
        }

        // Broadcast for any listeners
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STOPPED));
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

    private void onProximityChanged(float proximity) {
        boolean wakeOnProximity = sharedPreferences.getBoolean(SP_WAKE_ON_PROXIMITY, false);
        float maxRange = sensorManager.getMaxProximitySensorValue();

        // Publish to MQTT
        if (mqttServer != null && mqttServer.shouldSend()) {
            mqttServer.publishProximity(proximity);
        }

        // Wake on proximity if enabled and something is close
        if (wakeOnProximity && isDimmed && proximity < maxRange - 0.5f) {
            wakeScreen();
        }

        // Any proximity event counts as interaction
        lastInteractionTime = System.currentTimeMillis();
    }

    private void onLightChanged(float lux) {
        if (isDimmed) return; // Don't adjust while dimmed

        boolean autoBrightness = sharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true);
        if (!autoBrightness) return;

        final int brightness = calculateBrightnessFromLux(lux);
        mainHandler.post(() -> deviceHelper.setScreenBrightness(brightness));
    }

    private void onButtonEvent(int keyCode, boolean pressed) {
        // Log key events for debugging (controlled by debug flag)
        boolean debugKeys = sharedPreferences.getBoolean(SP_DEBUG_KEYS, false);
        if (debugKeys) {
            Log.i(TAG, "Button event: keyCode=" + keyCode + ", pressed=" + pressed);
        }

        // Any button press wakes the screen and resets idle timer
        if (pressed) {
            lastInteractionTime = System.currentTimeMillis();
            if (isDimmed) {
                wakeScreen();
            }
        }

        // Handle hardware buttons (key codes 59-62 for Shelly Wall Display XL)
        boolean handled = false;
        switch (keyCode) {
            case 59: // Button 1
                if (pressed) publishButton(1);
                handled = true;
                break;
            case 60: // Button 2
                if (pressed) publishButton(2);
                handled = true;
                break;
            case 61: // Button 3
                if (pressed) publishButton(3);
                handled = true;
                break;
            case 62: // Button 4
                if (pressed) publishButton(4);
                handled = true;
                break;
        }

        // Publish unknown keys to MQTT only when debug mode is enabled
        if (!handled && debugKeys && mqttServer != null && mqttServer.shouldSend()) {
            Log.w(TAG, "Unknown key code: " + keyCode + ", publishing to MQTT");
            mqttServer.publishUnknownKey(keyCode, pressed);
        }
    }

    private void publishButton(int buttonNum) {
        if (mqttServer != null && mqttServer.shouldSend()) {
            mqttServer.publishButton(buttonNum);
        }
    }

    public void onTouchEvent() {
        lastInteractionTime = System.currentTimeMillis();
        if (isDimmed) {
            wakeScreen();
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
        Log.i(TAG, "ShellyDisplayService stopping...");

        // Unregister receivers
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(proximityReceiver);
        lbm.unregisterReceiver(lightReceiver);
        lbm.unregisterReceiver(wakeSleepReceiver);

        // Stop input reader
        if (inputEventReader != null) {
            inputEventReader.stop();
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

        Log.i(TAG, "ShellyDisplayService stopped");
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
