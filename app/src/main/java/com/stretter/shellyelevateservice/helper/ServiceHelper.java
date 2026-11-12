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
import com.stretter.shellyelevateservice.WatchdogTarget;
import com.stretter.shellyelevateservice.dashboard.DashboardActivity;

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

    /**
     * Intent that brings the watchdog target to the front, or null if the package is not installed.
     * Our own dashboard is started explicitly: the app has no launcher activity, and
     * getLaunchIntentForPackage would not point at DashboardActivity.
     */
    public static Intent watchdogLaunchIntent(Context context, String target) {
        if (WatchdogTarget.isInternalDashboard(target, context.getPackageName())) {
            Intent intent = new Intent(context, DashboardActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            return intent;
        }
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(target);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        return intent;
    }
}
