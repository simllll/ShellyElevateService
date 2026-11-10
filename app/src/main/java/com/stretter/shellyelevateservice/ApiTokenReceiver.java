package com.stretter.shellyelevateservice;

import static com.stretter.shellyelevateservice.Constants.INTENT_API_TOKEN_KEY;
import static com.stretter.shellyelevateservice.Constants.INTENT_SET_API_TOKEN;
import static com.stretter.shellyelevateservice.Constants.SHARED_PREFERENCES_NAME;
import static com.stretter.shellyelevateservice.Constants.SP_HTTP_API_TOKEN;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Sets (or clears) the HTTP API token. Only reachable via adb: the manifest protects this
 * receiver with android.permission.DUMP, which the adb shell holds but normal apps cannot get.
 *
 * adb shell am broadcast -n com.stretter.shellyelevateservice/.ApiTokenReceiver \
 *     -a com.stretter.shellyelevateservice.SET_API_TOKEN --es token 'my-secret'
 */
public class ApiTokenReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!INTENT_SET_API_TOKEN.equals(intent.getAction())) return;

        String token = intent.getStringExtra(INTENT_API_TOKEN_KEY);
        token = token == null ? "" : token.trim();

        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SP_HTTP_API_TOKEN, token)
                .commit();

        String result = token.isEmpty() ? "HTTP API token cleared" : "HTTP API token set";
        Log.i("ApiTokenReceiver", result);
        setResultData(result);
    }
}
