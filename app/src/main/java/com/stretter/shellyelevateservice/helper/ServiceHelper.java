package com.stretter.shellyelevateservice.helper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import com.stretter.shellyelevateservice.ShellyElevateService;

public class ServiceHelper {

    @SuppressLint("ObsoleteSdkInt")
    public static boolean isNetworkReady(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return false;

            boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

            // Prefer validated when available; fall back to INTERNET to avoid false negatives right after boot.
            return validated || hasInternet;
        } else {
            // Legacy fallback (pre-M): rely on active network info
            try {
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                //noinspection deprecation
                return info != null && info.isConnected();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    public static void ensureElevateService(Context context) {
        Log.i("ShellyElevateService", "Starting ShellyElevateService...");
        Intent serviceIntent = new Intent(context, ShellyElevateService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
