package com.stretter.shellyelevateservice.mqtt;

import static com.stretter.shellyelevateservice.ShellyElevateApplication.mApplicationContext;
import static com.stretter.shellyelevateservice.Constants.*;
import static com.stretter.shellyelevateservice.ShellyElevateApplication.mDeviceHelper;
import static com.stretter.shellyelevateservice.ShellyElevateApplication.mMQTTServer;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.eclipse.paho.mqttv5.common.MqttMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.stretter.shellyelevateservice.ShellyElevateApplication;

/**
 * Dispatches inbound MQTT command messages. Called from the Paho callback thread.
 */
public class ShellyElevateMQTTCallback {
    // Toasts need a Looper thread, the Paho callback thread has none
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);

        if (MQTT_TOPIC_HOME_ASSISTANT_STATUS.equals(topic)) {
            // Home Assistant (re)started: re-send discovery and all current states.
            // A retained "online" is skipped too, we already published right after connecting.
            if (!message.isRetained() && "online".equals(payload.trim())) {
                Log.i("MQTT", "Home Assistant online, republishing discovery and states");
                mMQTTServer.publishStatus();
            }
            return;
        }

        // A retained command is replayed by the broker on every (re)subscribe. Never act on
        // those, otherwise e.g. a retained reboot command would reboot the device in a loop.
        if (message.isRetained()) {
            Log.w("MQTT", "Ignoring retained message on " + topic);
            return;
        }

        switch (topic.replace(mMQTTServer.getClientId(), "%s")) {
            case MQTT_TOPIC_UPDATE:
	            mMQTTServer.publishStatus();
	            break;
            case MQTT_TOPIC_RELAY_COMMAND:
                mDeviceHelper.setRelay(0, payload.contains("ON"));
                break;
            case MQTT_TOPIC_RELAY_COMMAND + "_1":
                mDeviceHelper.setRelay(1, payload.contains("ON"));
                break;
            case MQTT_TOPIC_SLEEP_BUTTON:
                // Broadcast to ShellyElevateService to dim screen
                Intent sleepIntent = new Intent(INTENT_SCREEN_SAVER_STARTED);
                LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(sleepIntent);
                break;
            case MQTT_TOPIC_WAKE_BUTTON:
                // Broadcast to ShellyElevateService to wake screen
                Intent wakeIntent = new Intent(INTENT_SCREEN_SAVER_STOPPED);
                LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(wakeIntent);
                break;
            case MQTT_TOPIC_REBOOT_BUTTON:
                long deltaTime = System.currentTimeMillis() - ShellyElevateApplication.getApplicationStartTime();
                deltaTime /= 1000;
                if (deltaTime > 20) {
                    try {
                        Runtime.getRuntime().exec("reboot");
                    } catch (IOException e) {
                        Log.e("MQTT", "Error rebooting:", e);
                    }
                } else {
                    String waitMessage = "Please wait %s seconds before rebooting".replace("%s", String.valueOf(20 - deltaTime));
                    mainHandler.post(() -> Toast.makeText(mApplicationContext, waitMessage, Toast.LENGTH_LONG).show());
                }
                break;
            case MQTT_TOPIC_SCREEN_BRIGHTNESS_COMMAND:
                try {
                    // HA number entities may send "128.0"
                    int brightness = (int) Math.round(Double.parseDouble(payload.trim()));
                    Intent brightnessIntent = new Intent(INTENT_SET_BRIGHTNESS);
                    brightnessIntent.putExtra(INTENT_BRIGHTNESS_KEY, Math.max(0, Math.min(255, brightness)));
                    LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(brightnessIntent);
                } catch (NumberFormatException e) {
                    Log.w("MQTT", "Ignoring invalid brightness: " + payload);
                }
                break;
            case MQTT_TOPIC_AUTO_BRIGHTNESS_COMMAND:
                Intent autoIntent = new Intent(INTENT_SET_AUTO_BRIGHTNESS);
                autoIntent.putExtra(INTENT_AUTO_BRIGHTNESS_KEY, "ON".equalsIgnoreCase(payload.trim()));
                LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(autoIntent);
                break;
            case MQTT_TOPIC_RESTART_APP_BUTTON:
                // Broadcast to ShellyElevateService to kill and restart the watchdog app
                Log.i("MQTT", "Restart app command received");
                Intent restartIntent = new Intent(INTENT_RESTART_APP);
                LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(restartIntent);
                break;
        }
    }
}
