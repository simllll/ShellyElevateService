package com.stretter.shellyelevateservice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.stretter.shellyelevateservice.helper.ServiceHelper;

/**
 * Starts ShellyElevateService on boot and after an app update (MY_PACKAGE_REPLACED).
 * The service handles MQTT, HTTP API, sensors, and screen dimming.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.i("BootReceiver", action + ", starting ShellyElevateService");
            ServiceHelper.ensureElevateService(context);
        }
    }
}
