package com.stretter.shellyelevateservice.mqtt;

import static com.stretter.shellyelevateservice.Constants.*;
import static com.stretter.shellyelevateservice.ShellyElevateApplication.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.stretter.shellyelevateservice.DeviceModel;

public class MQTTServer {

    private MqttClient mMqttClient;
    private final MemoryPersistence mMemoryPersistence;
    private final ShellyElevateMQTTCallback mShellyElevateMQTTCallback;
    private final MqttConnectionOptions mMqttConnectionsOptions;
    private final ScheduledExecutorService scheduler;
    private String clientId;
    private boolean validForConnection;
    private volatile boolean connecting = false;

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
        clientId = mSharedPreferences.getString(SP_MQTT_CLIENTID, "shellywalldisplay");
        if (clientId.equals("shellyelevate") || clientId.equals("shellywalldisplay") || clientId.length() <= 2) {
            clientId = "shellyelevate-" + UUID.randomUUID().toString().replaceAll("-", "").substring(2, 6);
            mSharedPreferences.edit().putString(SP_MQTT_CLIENTID, clientId).apply();
        }
    }

    private void registerSettingsReceiver() {
        BroadcastReceiver settingsChangedBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                checkCredsAndConnect();
            }
        };
        LocalBroadcastManager.getInstance(mApplicationContext)
                .registerReceiver(settingsChangedBroadcastReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));
    }

    private void schedulePeriodicTempHum() {
        scheduler.scheduleWithFixedDelay(this::publishTempAndHum, 0, 5, TimeUnit.SECONDS);
    }

    public void checkCredsAndConnect() {
        if (!isEnabled()) return;

        validForConnection =
                !mSharedPreferences.getString(SP_MQTT_PASSWORD, "").isEmpty() &&
                        !mSharedPreferences.getString(SP_MQTT_USERNAME, "").isEmpty() &&
                        !mSharedPreferences.getString(SP_MQTT_BROKER, "").isEmpty();

        connect();
    }

    public void connect() {
        if (!validForConnection || connecting || (mMqttClient != null && mMqttClient.isConnected())) return;

        connecting = true;
        Log.d("MQTT", "Connecting...");
        scheduler.execute(this::doConnect);
    }

    private void doConnect() {
        if (mMqttClient != null && mMqttClient.isConnected()) return;

        try {
            mMqttConnectionsOptions.setUserName(mSharedPreferences.getString(SP_MQTT_USERNAME, ""));
            mMqttConnectionsOptions.setPassword(mSharedPreferences.getString(SP_MQTT_PASSWORD, "").getBytes());
            mMqttConnectionsOptions.setAutomaticReconnect(true);
            mMqttConnectionsOptions.setConnectionTimeout(5);
            mMqttConnectionsOptions.setCleanStart(true);

            mMqttClient = new MqttClient(
                mSharedPreferences.getString(SP_MQTT_BROKER, "") + ":" + mSharedPreferences.getInt(SP_MQTT_PORT, 1883),
                clientId, mMemoryPersistence
            );

            // Set callback only once
            mMqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    Log.i("MQTT", "Connected to " + serverURI + ", reconnect: " + reconnect);
                    connecting = false;
                    safeOnConnected();
                }

                @Override
                public void disconnected(MqttDisconnectResponse disconnectResponse) {
                    Log.w("MQTT", "Disconnected: " + disconnectResponse.getReasonString());
                    connecting = false;
                    // automatically handled by reconnect
                }

                @Override
                public void mqttErrorOccurred(MqttException exception) {
                    Log.e("MQTT", "MQTT error occurred", exception);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    mShellyElevateMQTTCallback.messageArrived(topic, message);
                }

                @Override
                public void deliveryComplete(IMqttToken token) {}

                @Override
                public void authPacketArrived(int reasonCode, MqttProperties properties) {}
            });

            // LWT
            MqttMessage lwtMessage = new MqttMessage("offline".getBytes());
            lwtMessage.setQos(1);
            lwtMessage.setRetained(true);
            mMqttConnectionsOptions.setWill(parseTopic(MQTT_TOPIC_STATUS), lwtMessage);

            mMqttClient.connect(mMqttConnectionsOptions);
        } catch (MqttException e) {
            Log.e("MQTT", "Connect failed, scheduling retry in 60s: ", e);
            connecting = false;
            scheduler.schedule(this::connect, 60, TimeUnit.SECONDS);
        }
    }

    private void safeOnConnected() {
        scheduler.schedule(() -> {
            if (mMqttClient != null && mMqttClient.isConnected()) {
                try {
                    // Subscriptions
                    mMqttClient.subscribe("shellyelevateservice/" + clientId + "/#", 1);
                    mMqttClient.subscribe(MQTT_TOPIC_HOME_ASSISTANT_STATUS, 1);

                    publishStatus();
                } catch (Exception e) {
                    Log.e("MQTT", "onConnected error", e);
                }
            }
        }, 150, TimeUnit.MILLISECONDS);
    }

    public void publishStatus() {
        if (mMqttClient == null || !mMqttClient.isConnected()) return;

        scheduler.execute(() -> {
            try {
                // Publish hello info
                publishHello();

                // Publish config
                publishConfig();

                // Publish online status last
                publishInternal(parseTopic(MQTT_TOPIC_STATUS), "online", 1, true);

                // Stagger sensor publishes
                scheduler.schedule(this::publishTempAndHum, 50, TimeUnit.MILLISECONDS);
                for (int num = 0; num < DeviceModel.getReportedDevice().inputs; num++) {
                    int finalNum = num;
                    scheduler.schedule(() -> publishRelay(finalNum, mDeviceHelper.getRelay(finalNum)), 100, TimeUnit.MILLISECONDS);
                }
                scheduler.schedule(() -> publishLux(mDeviceSensorManager.getLastMeasuredLux()), 150, TimeUnit.MILLISECONDS);
                scheduler.schedule(() -> publishScreenBrightness(mDeviceHelper.getScreenBrightness()), 200, TimeUnit.MILLISECONDS);

                if (DeviceModel.getReportedDevice().hasProximitySensor) {
                    scheduler.schedule(() -> publishProximity(mDeviceSensorManager.getLastMeasuredDistance()), 250, TimeUnit.MILLISECONDS);
                }

                // Sleeping state is published by ShellyElevateService when it dims/wakes
                scheduler.schedule(() -> publishSleeping(false), 300, TimeUnit.MILLISECONDS);

            } catch (Exception e) {
                Log.e("MQTT", "publishStatus failed", e);
            }
        });
    }

    public void disconnect() {
        Log.d("MQTT", "Disconnecting");
        if (mMqttClient != null && mMqttClient.isConnected()) {
            try {
                deleteConfig();
                mMqttClient.publish(parseTopic(MQTT_TOPIC_STATUS), "offline".getBytes(), 1, true);
                mMqttClient.disconnect();
            } catch (MqttException e) {
                Log.e("MQTT", "Error disconnecting MQTT client", e);
            }
        }
    }

    public boolean isEnabled() {
        return mSharedPreferences.getBoolean(SP_MQTT_ENABLED, false);
    }

    public boolean shouldSend() {
        return isEnabled() && mMqttClient != null && mMqttClient.isConnected();
    }

    public void publishInternal(String topic, String payload, int qos, boolean retained) {
        if (!shouldSend()) {
            Log.w("MQTT", "publishInternal skipped — client not connected: " + topic);
            return;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(qos);
            message.setRetained(retained);
            mMqttClient.publish(topic, message);
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

    public void publishScreenBrightness(float val) {
        publishInternal(parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS), String.valueOf(val), 1, false);
    }
    public void publishProximity(float distance) {
        publishInternal(parseTopic(MQTT_TOPIC_PROXIMITY_SENSOR), String.valueOf(distance), 1, false);
    }

    public void publishRelay(int num, boolean state) {
        var mqttSuffix = (num >0 ? ("_" + num): "");
        publishInternal(parseTopic(MQTT_TOPIC_RELAY_STATE) + mqttSuffix, state ? "ON" : "OFF", 1, false);
    }

    public void publishSleeping(boolean state) {
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

            String version = "unknown";
            try {
                PackageInfo pInfo = mApplicationContext.getPackageManager()
                        .getPackageInfo(mApplicationContext.getPackageName(), 0);
                version = pInfo.versionName;
            } catch (PackageManager.NameNotFoundException ignored) {}

            json.put("version", version);
            var device = DeviceModel.getReportedDevice();
            json.put("modelName", device.name());
            json.put("proximity", device.hasProximitySensor ? "true" : "false");

            publishInternal(parseTopic(MQTT_TOPIC_HELLO), json.toString(), 1, false);
        } catch (JSONException e) {
            Log.e("MQTT", "Error publishing hello", e);
        }
    }

    private void publishConfig() throws JSONException, MqttException {
        JSONObject configPayload = new JSONObject();

        JSONObject device = new JSONObject();
        device.put("ids", clientId);
        device.put("name", "Shelly Wall Display");
        device.put("mf", "Shelly");
        configPayload.put("dev", device);

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
        tempSensorPayload.put("unique_id", clientId + "_temp");
        components.put(clientId + "_temp", tempSensorPayload);

        JSONObject humSensorPayload = new JSONObject();
        humSensorPayload.put("p", "sensor");
        humSensorPayload.put("name", "Humidity");
        humSensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_HUM_SENSOR));
        humSensorPayload.put("device_class", "humidity");
        humSensorPayload.put("unit_of_measurement", "%");
        humSensorPayload.put("unique_id", clientId + "_hum");
        components.put(clientId + "_hum", humSensorPayload);

        JSONObject luxSensorPayload = new JSONObject();
        luxSensorPayload.put("p", "sensor");
        luxSensorPayload.put("name", "Light");
        luxSensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_LUX_SENSOR));
        luxSensorPayload.put("device_class", "illuminance");
        luxSensorPayload.put("unit_of_measurement", "lx");
        luxSensorPayload.put("unique_id", clientId + "_lux");
        components.put(clientId + "_lux", luxSensorPayload);

        if (DeviceModel.getReportedDevice().hasProximitySensor) {
            JSONObject proximitySensorPayload = new JSONObject();
            proximitySensorPayload.put("p", "sensor");
            proximitySensorPayload.put("name", "Proximity");
            proximitySensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_PROXIMITY_SENSOR));
            proximitySensorPayload.put("device_class", "distance");
            proximitySensorPayload.put("unit_of_measurement", "cm");
            proximitySensorPayload.put("unique_id", clientId + "_proximity");
            components.put(clientId + "_proximity", proximitySensorPayload);
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

        // TODO: brightness as both state and control

        configPayload.put("cmps", components);

        configPayload.put("state_topic", MQTT_TOPIC_STATUS);

        mMqttClient.publish(parseTopic(MQTT_TOPIC_CONFIG_DEVICE), configPayload.toString().getBytes(), 1, true);
    }

    private void deleteConfig() throws MqttException {
        mMqttClient.publish(parseTopic(MQTT_TOPIC_CONFIG_DEVICE), "".getBytes(), 1, false);
    }

    private String parseTopic(String topic) {
        return topic.replace("%s", clientId);
    }

    public String getClientId() {
        return clientId;
    }

    public void onDestroy() {
        disconnect();
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdown();
    }
}
