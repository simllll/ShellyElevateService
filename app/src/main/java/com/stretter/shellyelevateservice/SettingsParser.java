package com.stretter.shellyelevateservice;

import static com.stretter.shellyelevateservice.Constants.SP_HTTP_API_TOKEN;
import static com.stretter.shellyelevateservice.Constants.SP_MQTT_PASSWORD;
import static com.stretter.shellyelevateservice.ShellyElevateApplication.mSharedPreferences;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SettingsParser {
    // Secrets are never returned by the HTTP API. Posting the mask back keeps the stored value.
    static final String SECRET_MASK = "********";
    private static final Set<String> SECRET_KEYS = new HashSet<>(Arrays.asList(SP_MQTT_PASSWORD, SP_HTTP_API_TOKEN));

    public JSONObject getSettings() throws JSONException {
        JSONObject settings = new JSONObject();
        Map<String, ?> allPreferences = mSharedPreferences.getAll();

        // Sort keys alphabetically
        List<String> sortedKeys = new ArrayList<>(allPreferences.keySet());
        Collections.sort(sortedKeys);

        for (String key : sortedKeys) {
            Object value = allPreferences.get(key);
            if (SECRET_KEYS.contains(key) && value instanceof String && !((String) value).isEmpty()) {
                value = SECRET_MASK;
            }
            settings.put(key, value);
        }
        return settings;
    }

    /**
     * True if posting this value would not change the stored setting
     * (including a secret posted back as its mask).
     */
    public boolean isUnchanged(String key, Object value) {
        if (SECRET_KEYS.contains(key) && SECRET_MASK.equals(value)) return true;
        Object current = mSharedPreferences.getAll().get(key);
        return current != null && String.valueOf(current).equals(String.valueOf(value));
    }

    /**
     * Stores the given settings. Existing keys keep their stored type (e.g. "1883" for the
     * int key mqttPort is stored as int); values that cannot be converted are rejected.
     * Either all settings are stored or none.
     *
     * @throws IllegalArgumentException listing every rejected key
     */
    public void setSettings(JSONObject settings) throws JSONException {
        Map<String, ?> existingPrefs = mSharedPreferences.getAll();
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        List<String> errors = new ArrayList<>();

        for (Iterator<String> it = settings.keys(); it.hasNext(); ) {
            String key = it.next();
            Object value = settings.get(key);
            if (SECRET_KEYS.contains(key) && SECRET_MASK.equals(value)) {
                continue;
            }
            try {
                put(editor, key, value, existingPrefs.get(key));
            } catch (IllegalArgumentException e) {
                errors.add(key + ": " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        editor.apply();
    }

    private static void put(SharedPreferences.Editor editor, String key, Object value, Object current) {
        if (value == null || value == JSONObject.NULL) {
            throw new IllegalArgumentException("null is not allowed");
        }

        if (current instanceof Integer) {
            editor.putInt(key, toInt(value));
        } else if (current instanceof Long) {
            editor.putLong(key, toLong(value));
        } else if (current instanceof Float) {
            editor.putFloat(key, toFloat(value));
        } else if (current instanceof Boolean) {
            editor.putBoolean(key, toBoolean(value));
        } else if (current instanceof String) {
            if (!(value instanceof String) && !(value instanceof Number)) {
                throw new IllegalArgumentException("expected a string");
            }
            editor.putString(key, String.valueOf(value));
        } else if (value instanceof String) {
            // Unknown key: store by JSON type
            editor.putString(key, (String) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Number) {
            editor.putFloat(key, ((Number) value).floatValue());
        } else {
            throw new IllegalArgumentException("unsupported value type");
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || Math.floor(d) != d) {
                throw new IllegalArgumentException("expected a whole number");
            }
            return (long) d;
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("expected a whole number");
            }
        }
        throw new IllegalArgumentException("expected a whole number");
    }

    private static int toInt(Object value) {
        long l = toLong(value);
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("number out of range");
        }
        return (int) l;
    }

    private static float toFloat(Object value) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        if (value instanceof String) {
            try {
                return Float.parseFloat(((String) value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("expected a number");
            }
        }
        throw new IllegalArgumentException("expected a number");
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        throw new IllegalArgumentException("expected true or false");
    }
}
