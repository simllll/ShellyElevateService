package io.github.simllll.panelelevate;

import static io.github.simllll.panelelevate.Constants.SHARED_PREFERENCES_NAME;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import io.github.simllll.panelelevate.helper.DeviceHelper;
import io.github.simllll.panelelevate.helper.DeviceSensorManager;
import io.github.simllll.panelelevate.helper.MediaHelper;
import io.github.simllll.panelelevate.mqtt.MQTTServer;

/**
 * Minimal Application class.
 * Static references are populated by PanelElevateService when it starts.
 */
public class PanelElevateApplication extends Application {

    // Components (initialized by PanelElevateService)
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

        Log.i("PanelElevateApp", "Application created, device: " + DeviceModel.getReportedDevice().modelName);
    }

    public static long getApplicationStartTime() {
        return applicationStartTime;
    }

    @Override
    public void onTerminate() {
        Log.i("PanelElevateApp", "Application terminating");
        super.onTerminate();
    }
}
