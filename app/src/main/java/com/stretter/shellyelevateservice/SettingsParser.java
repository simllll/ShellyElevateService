package com.stretter.shellyelevateservice;

import static com.stretter.shellyelevateservice.ShellyElevateApplication.mSharedPreferences;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SettingsParser {
    public JSONObject getSettings() throws JSONException {
        JSONObject settings = new JSONObject();
        Map<String, ?> allPreferences = mSharedPreferences.getAll();

        // Sort keys alphabetically
        List<String> sortedKeys = new ArrayList<>(allPreferences.keySet());
        Collections.sort(sortedKeys);

        for (String key : sortedKeys) {
            settings.put(key, allPreferences.get(key));
        }
        return settings;
    }

    public void setSettings(JSONObject settings) throws JSONException {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        for (Iterator<String> it = settings.keys(); it.hasNext(); ) {
            String key = it.next();
            Class<?> type = settings.get(key).getClass();
            if (type.equals(String.class)) {
                editor.putString(key, settings.getString(key));
            } else if (type.equals(Integer.class)) {
                editor.putInt(key, settings.getInt(key));
            } else if (type.equals(Long.class)) {
                editor.putLong(key, settings.getLong(key));
            } else if (type.equals(Boolean.class)) {
                editor.putBoolean(key, settings.getBoolean(key));
            }
        }
        editor.apply();
    }
}
