package com.stretter.shellyelevateservice;

import static com.stretter.shellyelevateservice.Constants.INTENT_SETTINGS_CHANGED;
import static com.stretter.shellyelevateservice.Constants.INTENT_SETTING_KEY;
import static com.stretter.shellyelevateservice.Constants.INTENT_SETTING_VALUE;
import static com.stretter.shellyelevateservice.Constants.INTENT_SET_SETTING;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Changes a whitelisted setting (watchdogPackage, watchdogEnabled, watchdogInterval, bleProxyEnabled)
 * without the HTTP API token. Only reachable via adb: the manifest protects this receiver with
 * android.permission.DUMP, which the adb shell holds but normal apps cannot get.
 *
 * adb shell am broadcast -n com.stretter.shellyelevateservice/.AdbSettingReceiver \
 *     -a com.stretter.shellyelevateservice.SET_SETTING --es key watchdogPackage --es value dashboard
 */
public class AdbSettingReceiver extends BroadcastReceiver {
    private static final String TAG = "AdbSettingReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!INTENT_SET_SETTING.equals(intent.getAction())) return;

        String key = intent.getStringExtra(INTENT_SETTING_KEY);
        String value = intent.getStringExtra(INTENT_SETTING_VALUE);

        String result;
        try {
            Object typed = WatchdogTarget.parseAdbSetting(key, value);
            JSONObject settings = new JSONObject();
            settings.put(key, typed);
            new SettingsParser().setSettings(settings);

            LocalBroadcastManager.getInstance(context.getApplicationContext())
                    .sendBroadcast(new Intent(INTENT_SETTINGS_CHANGED));
            result = "Set " + key + " = " + typed;
            Log.i(TAG, result);
        } catch (IllegalArgumentException | JSONException e) {
            result = "Rejected " + key + ": " + e.getMessage();
            Log.w(TAG, result);
        }
        setResultData(result);
    }
}
