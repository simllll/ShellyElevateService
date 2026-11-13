package io.github.simllll.panelelevate.helper;

import static io.github.simllll.panelelevate.Constants.INTENT_LIGHT_KEY;
import static io.github.simllll.panelelevate.Constants.INTENT_LIGHT_UPDATED;
import static io.github.simllll.panelelevate.Constants.INTENT_PROXIMITY_KEY;
import static io.github.simllll.panelelevate.Constants.INTENT_PROXIMITY_UPDATED;
import static io.github.simllll.panelelevate.Constants.SP_AUTOMATIC_BRIGHTNESS;
import static io.github.simllll.panelelevate.Constants.SP_MIN_BRIGHTNESS;
import static io.github.simllll.panelelevate.Constants.SP_WAKE_ON_PROXIMITY;
import static io.github.simllll.panelelevate.PanelElevateApplication.mMQTTServer;
import static io.github.simllll.panelelevate.PanelElevateApplication.mSharedPreferences;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Arrays;
import java.util.List;

import io.github.simllll.panelelevate.DeviceModel;
import io.github.simllll.panelelevate.PanelElevateApplication;


public class DeviceSensorManager implements SensorEventListener {
    private static final String TAG = "DeviceSensorManager";
    private float lastMeasuredLux = 0.0f;
    private float lastPublishedLux = -1f; // initialize to invalid value
    private long lastLuxPublishTime = 0L;

    // Lux publishing: only significant changes, rate limited. Small absolute changes at low
    // light levels are sensor noise and would otherwise flood MQTT/Home Assistant.
    private static final float LUX_MIN_ABS_CHANGE = 2f;
    private static final float LUX_MIN_REL_CHANGE = 0.10f;
    private static final long LUX_MIN_INTERVAL_MS = 10_000L;
    private static final long LUX_MAX_INTERVAL_MS = 300_000L;

    private final Context context;

    public DeviceSensorManager(Context ctx) {
        context = ctx;

        DeviceModel device = DeviceModel.getReportedDevice();
        SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        List<Sensor> deviceSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor sensor : deviceSensors) {
            Log.d(TAG, sensor.getName());
        }

        // light sensor
        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);

        // proximity sensor
        Log.d(TAG, "Has proximity sensor: " + device.hasProximitySensor);
        if (device.hasProximitySensor) {
            Sensor proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (proximitySensor != null) {
                maxProximitySensorValue = proximitySensor.getMaximumRange();
                Log.d(TAG, "Default proximity sensor: " + proximitySensor + " - Max: " + maxProximitySensorValue);
                sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
    }

    public float getLastMeasuredLux() {
        return lastMeasuredLux;
    }

    private float lastMeasuredDistance = 0.0f;
    public float getLastMeasuredDistance() { return lastMeasuredDistance; }

    private float maxProximitySensorValue = 1.0f;
    public float getMaxProximitySensorValue() { return maxProximitySensorValue;}

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null) return;
        Intent intent;

        //Log.d(TAG, "Got an event from a sensor: " + event.sensor.getType() + " - " + Arrays.toString(event.values));

        switch (event.sensor.getType()) {
            case Sensor.TYPE_LIGHT:
                lastMeasuredLux = event.values[0];
                boolean shouldPublish = false;

                long now = System.currentTimeMillis();
                if (lastPublishedLux < 0) {
                    // First reading
                    shouldPublish = true;
                } else {
                    float diff = Math.abs(lastMeasuredLux - lastPublishedLux);
                    // Math.max avoids dividing by zero in the dark (0 lux)
                    float change = diff / Math.max(lastPublishedLux, 1f);
                    long sinceLast = now - lastLuxPublishTime;
                    boolean significant = diff >= LUX_MIN_ABS_CHANGE && change >= LUX_MIN_REL_CHANGE;
                    shouldPublish = (significant && sinceLast >= LUX_MIN_INTERVAL_MS)
                            || (diff > 0f && sinceLast >= LUX_MAX_INTERVAL_MS);
                }

                if (shouldPublish && mMQTTServer != null && mMQTTServer.shouldSend()) {
                    mMQTTServer.publishLux(lastMeasuredLux);
                    lastPublishedLux = lastMeasuredLux;
                    lastLuxPublishTime = now;
                }

                // Always broadcast locally for UI updates, even if not published
                intent = new Intent(INTENT_LIGHT_UPDATED);
                intent.putExtra(INTENT_LIGHT_KEY, lastMeasuredLux);
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
                break;

            case Sensor.TYPE_PROXIMITY:
                lastMeasuredDistance = event.values[0];

                //Let everyone know we got a new proximity value
                intent = new Intent(INTENT_PROXIMITY_UPDATED);
                intent.putExtra(INTENT_PROXIMITY_KEY, lastMeasuredDistance);
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Ignore
    }

    public void onDestroy() {
        ((SensorManager) context.getSystemService(Context.SENSOR_SERVICE)).unregisterListener(this);
    }
}