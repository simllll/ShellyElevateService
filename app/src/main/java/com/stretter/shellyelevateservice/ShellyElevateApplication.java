package com.stretter.shellyelevateservice;

import static com.stretter.shellyelevateservice.Constants.SHARED_PREFERENCES_NAME;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.stretter.shellyelevateservice.helper.DeviceHelper;
import com.stretter.shellyelevateservice.helper.DeviceSensorManager;
import com.stretter.shellyelevateservice.helper.MediaHelper;
import com.stretter.shellyelevateservice.mqtt.MQTTServer;

/**
 * Minimal Application class.
 * Static references are populated by ShellyElevateService when it starts.
 */
public class ShellyElevateApplication extends Application {

    // Components (initialized by ShellyElevateService)
    public static HttpServer mHttpServer;
    public static DeviceHelper mDeviceHelper;
    public static DeviceSensorManager mDeviceSensorManager;
    public static MQTTServer mMQTTServer;
    public static MediaHelper mMediaHelper;

    // Context and preferences
    public static Context mApplicationContext;
    public static SharedPreferences mSharedPreferences;

    private static long applicationStartTime;

    @Override
    public void onCreate() {
        super.onCreate();

        // Set up crash handler
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));

        applicationStartTime = System.currentTimeMillis();

        // Initialize context and preferences (needed before service starts)
        mApplicationContext = getApplicationContext();
        mSharedPreferences = getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE);

        Log.i("ShellyElevateApp", "Application created, device: " + DeviceModel.getReportedDevice().modelName);
    }

    public static long getApplicationStartTime() {
        return applicationStartTime;
    }

    @Override
    public void onTerminate() {
        Log.i("ShellyElevateApp", "Application terminating");
        super.onTerminate();
    }
}
