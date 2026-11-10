package com.stretter.shellyelevateservice.mqtt;

import static com.stretter.shellyelevateservice.Constants.*;
import static com.stretter.shellyelevateservice.ShellyElevateApplication.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.stretter.shellyelevateservice.DeviceModel;

public class MQTTServer {

    private static final long RECONNECT_DELAY_SEC = 5;
    private static final long TEMP_HUM_INTERVAL_SEC = 30;
    private static final long MIN_BRIGHTNESS_PUBLISH_INTERVAL_MS = 500;
    private static final long CONNECT_RETRY_DELAY_SEC = 60;

    // Written on the scheduler thread, read from sensor/UI/HTTP threads
    private volatile MqttClient mMqttClient;
    private final MemoryPersistence mMemoryPersistence;
    private final ShellyElevateMQTTCallback mShellyElevateMQTTCallback;
    private final MqttConnectionOptions mMqttConnectionsOptions;
    // Single thread: connects, teardowns and publishes never interleave
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile String clientId;
    private volatile boolean validForConnection;
    // Connection settings the current client was built from
    private volatile String appliedConnectionKey = "";
    private BroadcastReceiver settingsChangedReceiver;
    // Last known screen-saver state, re-sent on (re)connect and when Home Assistant restarts
    private volatile boolean sleeping = false;
    // Last presence state from the gpio_keys presence sensor, null until the first event
    private volatile Boolean presence = null;

    // Brightness publish throttling, guarded by this
    private int lastPublishedBrightness = Integer.MIN_VALUE;
    private long lastBrightnessSentAtMs = 0L;
    private int pendingBrightness;
    // Trailing publish so the final value always goes out
    private ScheduledFuture<?> brightnessTrailingFuture;

    public MQTTServer() {
        mMemoryPersistence = new MemoryPersistence();
        mShellyElevateMQTTCallback = new ShellyElevateMQTTCallback();
        mMqttConnectionsOptions = new MqttConnectionOptions();
        scheduler = Executors.newScheduledThreadPool(1);

        setupClientId();
        registerSettingsReceiver();
        schedulePeriodicTempHum();

        checkCredsAndConnect();
    }

    private void setupClientId() {
        String id = mSharedPreferences.getString(SP_MQTT_CLIENTID, "shellywalldisplay");
        if (id.equals("shellyelevate") || id.equals("shellywalldisplay") || id.length() <= 2) {
            id = "shellyelevate-" + UUID.randomUUID().toString().replaceAll("-", "").substring(2, 6);
            mSharedPreferences.edit().putString(SP_MQTT_CLIENTID, id).apply();
        }
        clientId = id;
    }

    private void registerSettingsReceiver() {
        settingsChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reconnectWithNewSettings();
            }
        };
        LocalBroadcastManager.getInstance(mApplicationContext)
                .registerReceiver(settingsChangedReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));
    }

    private void schedulePeriodicTempHum() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                publishTempAndHum();
            } catch (Throwable t) {
                // an escaping exception would cancel the fixed-delay schedule for good
                Log.e("MQTT", "Periodic temp/humidity publish failed", t);
            }
        }, TEMP_HUM_INTERVAL_SEC, TEMP_HUM_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    // Scheduler helpers that never throw once the scheduler is shut down

    private boolean execute(Runnable task) {
        try {
            scheduler.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            Log.w("MQTT", "Task rejected, scheduler is shut down");
            return false;
        }
    }

    private ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        try {
            return scheduler.schedule(task, delay, unit);
        } catch (RejectedExecutionException e) {
            Log.w("MQTT", "Delayed task rejected, scheduler is shut down");
            return null;
        }
    }

    /**
     * Applies changed MQTT settings. If anything the connection depends on changed (enabled,
     * broker, port, credentials, device id) the old client is fully torn down and closed before
     * a new one is created, so there are never two clients with the same client id.
     */
    private void reconnectWithNewSettings() {
        execute(() -> {
            if (connectionKey().equals(appliedConnectionKey)) {
                // Unrelated setting changed - just make sure we are connected if we should be
                checkCredsAndConnect();
                publishAutoBrightness();
                return;
            }

            Log.i("MQTT", "Connection settings changed, reconnecting");
            MqttClient old = mMqttClient;
            // Detach first so callbacks of the old client are ignored from now on
            mMqttClient = null;
            if (old != null) {
                // Mark the old device unavailable (old client id / broker); a clean disconnect
                // does not trigger the will message
                publishOffline(old);
                closeClient(old);
            }
            connecting.set(false);

            setupClientId();
            checkCredsAndConnect();
        });
    }

    public void checkCredsAndConnect() {
        appliedConnectionKey = connectionKey();
        if (!isEnabled()) {
            MqttClient old = mMqttClient;
            if (old != null) {
                Log.i("MQTT", "MQTT disabled, disconnecting");
                execute(() -> {
                    if (mMqttClient != old) return;
                    mMqttClient = null;
                    publishOffline(old);
                    closeClient(old);
                });
            }
            return;
        }

        validForConnection =
                !mSharedPreferences.getString(SP_MQTT_PASSWORD, "").isEmpty() &&
                        !mSharedPreferences.getString(SP_MQTT_USERNAME, "").isEmpty() &&
                        !mSharedPreferences.getString(SP_MQTT_BROKER, "").isEmpty();

        connect();
    }

    public void connect() {
        // Re-check enabled: a delayed retry may fire after MQTT got disabled
        if (!isEnabled() || !validForConnection || isClientConnected()) return;
        if (!connecting.compareAndSet(false, true)) return;

        Log.d("MQTT", "Connecting...");
        if (!execute(this::doConnect)) connecting.set(false);
    }

    private void doConnect() {
        if (isClientConnected()) {
            connecting.set(false);
            return;
        }

        try {
            mMqttConnectionsOptions.setUserName(mSharedPreferences.getString(SP_MQTT_USERNAME, ""));
            mMqttConnectionsOptions.setPassword(mSharedPreferences.getString(SP_MQTT_PASSWORD, "").getBytes(StandardCharsets.UTF_8));
            // We reconnect ourselves (see disconnected()); Paho's automatic reconnect would keep
            // a replaced client alive and fight the new one over the same client id
            mMqttConnectionsOptions.setAutomaticReconnect(false);
            mMqttConnectionsOptions.setConnectionTimeout(5);
            mMqttConnectionsOptions.setCleanStart(true);

            // LWT: the broker marks us offline if we drop without a clean disconnect
            MqttMessage lwtMessage = new MqttMessage("offline".getBytes(StandardCharsets.UTF_8));
            lwtMessage.setQos(1);
            lwtMessage.setRetained(true);
            mMqttConnectionsOptions.setWill(parseTopic(MQTT_TOPIC_STATUS), lwtMessage);

            // Release a previous (failed) client so its network threads go away
            MqttClient previous = mMqttClient;
            mMqttClient = null;
            if (previous != null) closeClient(previous);

            MqttClient client = new MqttClient(buildServerUri(), clientId, mMemoryPersistence);
            client.setCallback(createClientCallback(client));
            // Never block a caller forever (e.g. publishing "offline" from onDestroy)
            client.setTimeToWait(10_000);
            mMqttClient = client;
            client.connect(mMqttConnectionsOptions);
        } catch (MqttException e) {
            Log.e("MQTT", "Connect failed, scheduling retry in " + CONNECT_RETRY_DELAY_SEC + "s: ", e);
            connecting.set(false);
            schedule(this::connect, CONNECT_RETRY_DELAY_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            // e.g. an invalid broker URI; a corrected setting triggers a new attempt
            Log.e("MQTT", "Connect failed with unexpected error", e);
            connecting.set(false);
        }
    }

    // Every setting the live connection is built from, so a change forces a reconnect
    private String connectionKey() {
        return isEnabled() + "\n" + buildServerUri() + "\n"
                + mSharedPreferences.getString(SP_MQTT_USERNAME, "") + "\n"
                + mSharedPreferences.getString(SP_MQTT_PASSWORD, "") + "\n"
                + mSharedPreferences.getString(SP_MQTT_CLIENTID, "");
    }

    private String buildServerUri() {
        String broker = mSharedPreferences.getString(SP_MQTT_BROKER, "").trim();
        // Default to plain TCP when the scheme is omitted
        if (!broker.isEmpty() && !broker.contains("://")) broker = "tcp://" + broker;
        return broker + ":" + mSharedPreferences.getInt(SP_MQTT_PORT, 1883);
    }

    private MqttCallback createClientCallback(MqttClient client) {
        return new MqttCallback() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.i("MQTT", "Connected to " + serverURI + ", reconnect: " + reconnect);
                if (client != mMqttClient) return;
                connecting.set(false);
                safeOnConnected();
            }

            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {
                Log.w("MQTT", "Disconnected: " + disconnectResponse.getReasonString());
                // A replaced client must not touch state that belongs to its successor
                if (client != mMqttClient) return;
                connecting.set(false);
                if (isEnabled() && validForConnection) {
                    schedule(MQTTServer.this::connect, RECONNECT_DELAY_SEC, TimeUnit.SECONDS);
                }
            }

            @Override
            public void mqttErrorOccurred(MqttException exception) {
                Log.e("MQTT", "MQTT error occurred", exception);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // An exception escaping here makes Paho drop the connection
                try {
                    mShellyElevateMQTTCallback.messageArrived(topic, message);
                } catch (Exception e) {
                    Log.e("MQTT", "Failed to handle message on " + topic, e);
                }
            }

            @Override
            public void deliveryComplete(IMqttToken token) {}

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        };
    }

    private void safeOnConnected() {
        // Some brokers reject a subscribe that shares the TCP write with the CONNACK
        schedule(() -> {
            MqttClient client = mMqttClient;
            if (client != null && client.isConnected()) {
                try {
                    // Only our command topics, never <base>/# (we would receive our own states)
                    for (String topic : commandTopics()) {
                        client.subscribe(topic, 1);
                    }
                    publishStatus();
                } catch (Exception e) {
                    Log.e("MQTT", "onConnected error", e);
                }
            }
        }, 150, TimeUnit.MILLISECONDS);
    }

    private String[] commandTopics() {
        return new String[]{
                parseTopic(MQTT_TOPIC_UPDATE),
                parseTopic(MQTT_TOPIC_RELAY_COMMAND),
                parseTopic(MQTT_TOPIC_RELAY_COMMAND) + "_1",
                parseTopic(MQTT_TOPIC_SLEEP_BUTTON),
                parseTopic(MQTT_TOPIC_WAKE_BUTTON),
                parseTopic(MQTT_TOPIC_REBOOT_BUTTON),
                parseTopic(MQTT_TOPIC_RESTART_APP_BUTTON),
                parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS_COMMAND),
                parseTopic(MQTT_TOPIC_AUTO_BRIGHTNESS_COMMAND),
                MQTT_TOPIC_HOME_ASSISTANT_STATUS
        };
    }

    /**
     * Full sync: discovery config, availability and all current states.
     * Called after (re)connecting, on the update command and when Home Assistant comes online.
     */
    public void publishStatus() {
        if (!isClientConnected()) return;

        execute(() -> {
            try {
                publishHello();
                publishConfig();
                // Availability after the config so HA knows the topic before it goes online
                publishInternal(parseTopic(MQTT_TOPIC_STATUS), "online", 1, true);
                publishCurrentStates();
            } catch (Exception e) {
                Log.e("MQTT", "publishStatus failed", e);
            }
        });
    }

    private void publishCurrentStates() {
        DeviceModel device = DeviceModel.getReportedDevice();
        // Each state on its own: one unreadable sensor must not block all the others
        safely("temp/hum", this::publishTempAndHum);
        for (int num = 0; num < device.inputs; num++) {
            final int relay = num;
            safely("relay " + relay, () -> publishRelay(relay, mDeviceHelper.getRelay(relay)));
        }
        safely("lux", () -> publishLux(mDeviceSensorManager.getLastMeasuredLux()));
        safely("brightness", () -> forcePublishScreenBrightness(mDeviceHelper.getScreenBrightness()));
        safely("auto brightness", this::publishAutoBrightness);
        if (device.hasProximitySensor) {
            safely("proximity", () -> publishProximity(mDeviceSensorManager.getLastMeasuredDistance()));
        }
        safely("sleeping", () -> publishSleeping(sleeping));
        Boolean lastPresence = presence;
        if (lastPresence != null) safely("presence", () -> publishPresence(lastPresence));
    }

    private static void safely(String what, Runnable publish) {
        try {
            publish.run();
        } catch (Exception e) {
            Log.e("MQTT", "Publishing " + what + " failed", e);
        }
    }

    /**
     * Marks the device unavailable in Home Assistant. Deliberately does NOT clear the
     * discovery config: an empty config deletes the device (and its customizations) in HA.
     */
    private void publishOffline(MqttClient client) {
        if (client == null || !client.isConnected()) return;
        try {
            client.publish(parseTopic(MQTT_TOPIC_STATUS), "offline".getBytes(StandardCharsets.UTF_8), 1, true);
        } catch (MqttException e) {
            Log.w("MQTT", "Failed to publish offline status", e);
        }
    }

    private static void closeClient(MqttClient client) {
        try {
            if (client.isConnected()) client.disconnect();
        } catch (MqttException e) {
            Log.w("MQTT", "Clean disconnect failed, forcing it", e);
            try {
                client.disconnectForcibly();
            } catch (MqttException forced) {
                Log.w("MQTT", "Forced disconnect failed", forced);
            }
        }
        try {
            client.close();
        } catch (MqttException e) {
            Log.w("MQTT", "Error closing MQTT client", e);
        }
    }

    public void disconnect() {
        Log.d("MQTT", "Disconnecting");
        MqttClient client = mMqttClient;
        mMqttClient = null;
        if (client != null) {
            publishOffline(client);
            closeClient(client);
        }
    }

    public boolean isEnabled() {
        return mSharedPreferences.getBoolean(SP_MQTT_ENABLED, false);
    }

    private boolean isClientConnected() {
        MqttClient client = mMqttClient;
        return client != null && client.isConnected();
    }

    public boolean shouldSend() {
        return isEnabled() && isClientConnected();
    }

    public void publishInternal(String topic, String payload, int qos, boolean retained) {
        MqttClient client = mMqttClient;
        if (!isEnabled() || client == null || !client.isConnected()) {
            Log.w("MQTT", "publishInternal skipped — client not connected: " + topic);
            return;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retained);
            client.publish(topic, message);
        } catch (MqttException e) {
            Log.e("MQTT", "Failed to publish to " + topic, e);
        }
    }

    public void publishTempAndHum() {
        float temp = (float) mDeviceHelper.getTemperature();
        float hum = (float) mDeviceHelper.getHumidity();
        publishTemp(temp);
        publishHum(hum);
    }

    public void publishTemp(float temp) {
        if (temp == -999) return;
        publishInternal(parseTopic(MQTT_TOPIC_TEMP_SENSOR), String.valueOf(temp), 1, false);
    }

    public void publishHum(float hum) {
        if (hum == -999) return;
        publishInternal(parseTopic(MQTT_TOPIC_HUM_SENSOR), String.valueOf(hum), 1, false);
    }

    public void publishLux(float lux) {
        publishInternal(parseTopic(MQTT_TOPIC_LUX_SENSOR), String.valueOf(lux), 1, false);
    }

    /**
     * Publishes the screen brightness, throttled to one message per 500 ms. Values arriving
     * within the window are coalesced into one trailing publish of the latest value.
     */
    public void publishScreenBrightness(int brightness) {
        if (!shouldSend()) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            long since = now - lastBrightnessSentAtMs;
            if (since < MIN_BRIGHTNESS_PUBLISH_INTERVAL_MS) {
                pendingBrightness = brightness;
                if (brightnessTrailingFuture == null) {
                    brightnessTrailingFuture = schedule(this::publishTrailingBrightness,
                            MIN_BRIGHTNESS_PUBLISH_INTERVAL_MS - since, TimeUnit.MILLISECONDS);
                }
                return;
            }
            // A newer value supersedes a pending trailing publish
            if (brightnessTrailingFuture != null) {
                brightnessTrailingFuture.cancel(false);
                brightnessTrailingFuture = null;
            }
            if (brightness == lastPublishedBrightness) return;
            lastPublishedBrightness = brightness;
            lastBrightnessSentAtMs = now;
        }
        execute(() -> publishInternal(parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS), String.valueOf(brightness), 1, false));
    }

    private void publishTrailingBrightness() {
        int value;
        synchronized (this) {
            brightnessTrailingFuture = null;
            value = pendingBrightness;
            if (value == lastPublishedBrightness) return;
            lastPublishedBrightness = value;
            lastBrightnessSentAtMs = SystemClock.elapsedRealtime();
        }
        publishInternal(parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS), String.valueOf(value), 1, false);
    }

    // Full state sync: always send, bypassing the "unchanged" check
    private void forcePublishScreenBrightness(int brightness) {
        synchronized (this) {
            lastPublishedBrightness = brightness;
            lastBrightnessSentAtMs = SystemClock.elapsedRealtime();
        }
        publishInternal(parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS), String.valueOf(brightness), 1, false);
    }

    public void publishAutoBrightness() {
        boolean auto = mSharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true);
        publishInternal(parseTopic(MQTT_TOPIC_AUTO_BRIGHTNESS_STATE), auto ? "ON" : "OFF", 1, false);
    }
    public void publishProximity(float distance) {
        publishInternal(parseTopic(MQTT_TOPIC_PROXIMITY_SENSOR), String.valueOf(distance), 1, false);
    }

    public void publishRelay(int num, boolean state) {
        var mqttSuffix = (num >0 ? ("_" + num): "");
        publishInternal(parseTopic(MQTT_TOPIC_RELAY_STATE) + mqttSuffix, state ? "ON" : "OFF", 1, false);
    }

    /** Records the presence (occupancy) state and publishes it if connected. */
    public void publishPresence(boolean present) {
        presence = present;
        publishInternal(parseTopic(MQTT_TOPIC_PRESENCE), present ? "ON" : "OFF", 1, false);
    }

    /** Records the screen-saver state and publishes it if connected. */
    public void publishSleeping(boolean state) {
        sleeping = state;
        publishInternal(parseTopic(MQTT_TOPIC_SLEEPING_BINARY_SENSOR), state ? "ON" : "OFF", 1, false);
    }

    /**
     * Publish a button event with specific event type (single, double, long).
     * This is the preferred method for button events as it supports all action types.
     */
    public void publishButtonEvent(int buttonNumber, String eventType) {
        String eventPayload = "{\"event_type\": \"" + eventType + "\"}";
        publishInternal(parseTopic(MQTT_TOPIC_BUTTON_EVENT) + "/" + buttonNumber, eventPayload, 1, false);
        Log.i("MQTT", "Published button " + buttonNumber + " event: " + eventType);
    }

    public void publishUnknownKey(int keyCode, boolean pressed) {
        String payload = "{\"key_code\": " + keyCode + ", \"pressed\": " + pressed + ", \"timestamp\": " + System.currentTimeMillis() + "}";
        publishInternal(parseTopic(MQTT_TOPIC_UNKNOWN_KEY), payload, 1, false);
        Log.i("MQTT", "Published unknown key: " + payload);
    }

    public void publishHello() {
        if (!shouldSend()) return;
        try {
            JSONObject json = new JSONObject();
            json.put("name", mApplicationContext.getPackageName());
            json.put("version", getVersionName());
            var device = DeviceModel.getReportedDevice();
            json.put("modelName", device.name());
            json.put("proximity", device.hasProximitySensor ? "true" : "false");

            publishInternal(parseTopic(MQTT_TOPIC_HELLO), json.toString(), 1, false);
        } catch (JSONException e) {
            Log.e("MQTT", "Error publishing hello", e);
        }
    }

    private void publishConfig() throws JSONException {
        JSONObject configPayload = new JSONObject();

        DeviceModel model = DeviceModel.getReportedDevice();
        JSONObject device = new JSONObject();
        device.put("ids", clientId);
        device.put("name", "Shelly Wall Display");
        device.put("mf", "Shelly");
        device.put("mdl", model.friendlyName);
        device.put("mdl_id", model.modelName);
        device.put("sw", getVersionName());
        configPayload.put("dev", device);

        // Shared by all components: entities become unavailable when the device is offline
        // (retained "online" on connect, "offline" as will message and on shutdown)
        configPayload.put("availability_topic", parseTopic(MQTT_TOPIC_STATUS));

        JSONObject origin = new JSONObject();
        origin.put("name", "ShellyElevateV2");
        origin.put("url", "https://github.com/RapierXbox/ShellyElevate");
        configPayload.put("o", origin);

        JSONObject components = new JSONObject();

        JSONObject tempSensorPayload = new JSONObject();
        tempSensorPayload.put("p", "sensor");
        tempSensorPayload.put("name", "Temperature");
        tempSensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_TEMP_SENSOR));
        tempSensorPayload.put("device_class", "temperature");
        tempSensorPayload.put("unit_of_measurement", "°C");
        tempSensorPayload.put("state_class", "measurement");
        tempSensorPayload.put("unique_id", clientId + "_temp");
        components.put(clientId + "_temp", tempSensorPayload);

        JSONObject humSensorPayload = new JSONObject();
        humSensorPayload.put("p", "sensor");
        humSensorPayload.put("name", "Humidity");
        humSensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_HUM_SENSOR));
        humSensorPayload.put("device_class", "humidity");
        humSensorPayload.put("unit_of_measurement", "%");
        humSensorPayload.put("state_class", "measurement");
        humSensorPayload.put("unique_id", clientId + "_hum");
        components.put(clientId + "_hum", humSensorPayload);

        JSONObject luxSensorPayload = new JSONObject();
        luxSensorPayload.put("p", "sensor");
        luxSensorPayload.put("name", "Light");
        luxSensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_LUX_SENSOR));
        luxSensorPayload.put("device_class", "illuminance");
        luxSensorPayload.put("unit_of_measurement", "lx");
        luxSensorPayload.put("state_class", "measurement");
        luxSensorPayload.put("unique_id", clientId + "_lux");
        components.put(clientId + "_lux", luxSensorPayload);

        if (DeviceModel.getReportedDevice().hasProximitySensor) {
            JSONObject proximitySensorPayload = new JSONObject();
            proximitySensorPayload.put("p", "sensor");
            proximitySensorPayload.put("name", "Proximity");
            proximitySensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_PROXIMITY_SENSOR));
            proximitySensorPayload.put("device_class", "distance");
            proximitySensorPayload.put("unit_of_measurement", "cm");
            proximitySensorPayload.put("state_class", "measurement");
            proximitySensorPayload.put("unique_id", clientId + "_proximity");
            components.put(clientId + "_proximity", proximitySensorPayload);

            // Wide-range presence sensor (gpio_keys KEY_F5 near / KEY_F6 far)
            JSONObject presencePayload = new JSONObject();
            presencePayload.put("p", "binary_sensor");
            presencePayload.put("name", "Presence");
            presencePayload.put("state_topic", parseTopic(MQTT_TOPIC_PRESENCE));
            presencePayload.put("device_class", "occupancy");
            presencePayload.put("unique_id", clientId + "_presence");
            components.put(clientId + "_presence", presencePayload);
        }

        // buttons (numbered 1-4 for user-friendliness)
        // Using device triggers (events) with single/double/long press support
        var buttons = DeviceModel.getReportedDevice().buttons;
        if (buttons > 0) {
            for (int i = 1; i <= buttons; i++) {
                // Event entity for automations - supports single, double, and long press
                JSONObject eventPayload = new JSONObject();
                eventPayload.put("p", "event");
                eventPayload.put("name", "Button " + i);
                eventPayload.put("state_topic", parseTopic(MQTT_TOPIC_BUTTON_EVENT) + "/" + i);
                eventPayload.put("device_class", "button");
                // Register all supported event types
                JSONArray eventTypes = new JSONArray();
                eventTypes.put("single");
                eventTypes.put("double");
                eventTypes.put("long");
                eventPayload.put("event_types", eventTypes);
                eventPayload.put("unique_id", clientId + "_button_" + i + "_event");
                components.put(clientId + "_button_" + i + "_event", eventPayload);
            }
        }

        for (int num = 0; num < DeviceModel.getReportedDevice().inputs; num++) {
            String mqttSuffix = (num >0 ? ("_" + num): "");
            // relay
            JSONObject relaySwitchPayload = new JSONObject();
            relaySwitchPayload.put("p", "switch");
            relaySwitchPayload.put("name", ("Relay " + (num >0 ? (" " + num): "")).trim());
            relaySwitchPayload.put("state_topic", parseTopic(MQTT_TOPIC_RELAY_STATE) + mqttSuffix);
            relaySwitchPayload.put("command_topic", parseTopic(MQTT_TOPIC_RELAY_COMMAND) + mqttSuffix);
            relaySwitchPayload.put("device_class", "outlet");
            relaySwitchPayload.put("unique_id", clientId + "_relay" + (num >0 ? ("_" + num): ""));
            components.put(clientId + "_relay" + (num >0 ? ("_" + num): ""), relaySwitchPayload);
        }

        JSONObject sleepButtonPayload = new JSONObject();
        sleepButtonPayload.put("p", "button");
        sleepButtonPayload.put("name", "Sleep");
        sleepButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_SLEEP_BUTTON));
        sleepButtonPayload.put("unique_id", clientId + "_sleep");
        components.put(clientId + "_sleep", sleepButtonPayload);

        JSONObject wakeButtonPayload = new JSONObject();
        wakeButtonPayload.put("p", "button");
        wakeButtonPayload.put("name", "Wake");
        wakeButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_WAKE_BUTTON));
        wakeButtonPayload.put("unique_id", clientId + "_wake");
        components.put(clientId + "_wake", wakeButtonPayload);

        JSONObject rebootButtonPayload = new JSONObject();
        rebootButtonPayload.put("p", "button");
        rebootButtonPayload.put("name", "Reboot");
        rebootButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_REBOOT_BUTTON));
        rebootButtonPayload.put("device_class", "restart");
        rebootButtonPayload.put("unique_id", clientId + "_reboot");
        components.put(clientId + "_reboot", rebootButtonPayload);

        JSONObject restartAppButtonPayload = new JSONObject();
        restartAppButtonPayload.put("p", "button");
        restartAppButtonPayload.put("name", "Restart App");
        restartAppButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_RESTART_APP_BUTTON));
        restartAppButtonPayload.put("device_class", "restart");
        restartAppButtonPayload.put("unique_id", clientId + "_restart_app");
        components.put(clientId + "_restart_app", restartAppButtonPayload);

        JSONObject sleepingBinarySensorPayload = new JSONObject();
        sleepingBinarySensorPayload.put("p", "binary_sensor");
        sleepingBinarySensorPayload.put("name", "Sleeping");
        sleepingBinarySensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_SLEEPING_BINARY_SENSOR));
        sleepingBinarySensorPayload.put("unique_id", clientId + "_sleeping");
        components.put(clientId + "_sleeping", sleepingBinarySensorPayload);

        JSONObject brightnessPayload = new JSONObject();
        brightnessPayload.put("p", "number");
        brightnessPayload.put("name", "Screen Brightness");
        brightnessPayload.put("state_topic", parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS));
        brightnessPayload.put("command_topic", parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS_COMMAND));
        brightnessPayload.put("min", 0);
        brightnessPayload.put("max", 255);
        brightnessPayload.put("step", 1);
        brightnessPayload.put("mode", "slider");
        brightnessPayload.put("icon", "mdi:brightness-6");
        brightnessPayload.put("unique_id", clientId + "_screen_brightness");
        components.put(clientId + "_screen_brightness", brightnessPayload);

        JSONObject autoBrightnessPayload = new JSONObject();
        autoBrightnessPayload.put("p", "switch");
        autoBrightnessPayload.put("name", "Auto Brightness");
        autoBrightnessPayload.put("state_topic", parseTopic(MQTT_TOPIC_AUTO_BRIGHTNESS_STATE));
        autoBrightnessPayload.put("command_topic", parseTopic(MQTT_TOPIC_AUTO_BRIGHTNESS_COMMAND));
        autoBrightnessPayload.put("entity_category", "config");
        autoBrightnessPayload.put("icon", "mdi:brightness-auto");
        autoBrightnessPayload.put("unique_id", clientId + "_auto_brightness");
        components.put(clientId + "_auto_brightness", autoBrightnessPayload);

        configPayload.put("cmps", components);

        publishInternal(parseTopic(MQTT_TOPIC_CONFIG_DEVICE), configPayload.toString(), 1, true);
    }

    private static String getVersionName() {
        try {
            PackageInfo pInfo = mApplicationContext.getPackageManager()
                    .getPackageInfo(mApplicationContext.getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private String parseTopic(String topic) {
        return topic.replace("%s", clientId);
    }

    public String getClientId() {
        return clientId;
    }

    public void onDestroy() {
        if (settingsChangedReceiver != null) {
            LocalBroadcastManager.getInstance(mApplicationContext).unregisterReceiver(settingsChangedReceiver);
        }
        // Stop scheduling reconnects/publishes, then go offline (availability only) and close
        scheduler.shutdownNow();
        disconnect();
    }
}
