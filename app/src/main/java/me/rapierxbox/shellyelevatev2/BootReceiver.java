package me.rapierxbox.shellyelevatev2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import me.rapierxbox.shellyelevatev2.helper.ServiceHelper;

/**
 * Starts ShellyDisplayService on boot.
 * The service handles MQTT, HTTP API, sensors, and screen dimming.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i("BootReceiver", "Boot completed, starting ShellyDisplayService");
            ServiceHelper.ensureDisplayService(context);
        }
    }
}
