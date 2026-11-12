package com.stretter.shellyelevateservice;

import static com.stretter.shellyelevateservice.Constants.INTENT_SETTINGS_CHANGED;
import static com.stretter.shellyelevateservice.Constants.INTENT_SCREEN_SAVER_STARTED;
import static com.stretter.shellyelevateservice.Constants.INTENT_SCREEN_SAVER_STOPPED;
import static com.stretter.shellyelevateservice.Constants.SP_HTTP_API_TOKEN;
import static com.stretter.shellyelevateservice.Constants.SP_HTTP_SERVER_ENABLED;
import static com.stretter.shellyelevateservice.Constants.SP_MQTT_BROKER;
import static com.stretter.shellyelevateservice.Constants.SP_MQTT_CLIENTID;
import static com.stretter.shellyelevateservice.Constants.SP_MQTT_PORT;
import static com.stretter.shellyelevateservice.Constants.SP_MQTT_USERNAME;
import static com.stretter.shellyelevateservice.Constants.SP_WATCHDOG_PACKAGE;
import static com.stretter.shellyelevateservice.ShellyElevateApplication.mApplicationContext;
import static com.stretter.shellyelevateservice.ShellyElevateApplication.mDeviceHelper;
import static com.stretter.shellyelevateservice.ShellyElevateApplication.mDeviceSensorManager;
import static com.stretter.shellyelevateservice.ShellyElevateApplication.mMediaHelper;
import static com.stretter.shellyelevateservice.ShellyElevateApplication.mSharedPreferences;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.stretter.shellyelevateservice.helper.ServiceHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends NanoHTTPD {
    /**
     * Settings that redirect where the device talks to (MQTT broker/identity) or which app it
     * keeps in front. Without an API token they cannot be changed over the HTTP API at all.
     */
    private static final Set<String> PROTECTED_SETTINGS = new HashSet<>(Arrays.asList(
            SP_MQTT_BROKER, SP_MQTT_PORT, SP_MQTT_USERNAME, SP_MQTT_CLIENTID, SP_WATCHDOG_PACKAGE,
            SP_HTTP_API_TOKEN));

    SettingsParser mSettingsParser = new SettingsParser();
    private final BroadcastReceiver settingsChangedBroadcastReceiver;

    public HttpServer() {
        super(8080);

        LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(mApplicationContext);
        settingsChangedBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true) && !isAlive()) {
                    try {
                        start();
                    } catch (IOException e) {
                        Log.d("HttpServer", "Failed to start http server: " + e);
                    }
                } else if (!mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true) && isAlive()) {
                    stop();
                }
            }
        };
        localBroadcastManager.registerReceiver(settingsChangedBroadcastReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));
    }

    private static Response errorResponse(Response.IStatus status, String message) {
        JSONObject json = new JSONObject();
        try {
            json.put("success", false);
            json.put("error", message);
        } catch (JSONException ignored) {}
        return newFixedLengthResponse(status, "application/json", json.toString());
    }

    // Asserts are disabled on Android, so a missing body needs a real check
    private static Response badRequest(String message) {
        return errorResponse(Response.Status.BAD_REQUEST, message);
    }

    private static String getApiToken() {
        return mSharedPreferences.getString(SP_HTTP_API_TOKEN, "");
    }

    /**
     * If an API token is configured every request must carry "Authorization: Bearer <token>".
     */
    private static boolean isAuthorized(IHTTPSession session) {
        String token = getApiToken();
        if (token.isEmpty()) return true;

        String header = session.getHeaders().get("authorization"); // NanoHTTPD lower-cases names
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        byte[] presented = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();
        String uri = session.getUri();
        JSONObject jsonResponse = new JSONObject();

        if (!isAuthorized(session)) {
            Response response = errorResponse(Response.Status.UNAUTHORIZED, "Missing or invalid API token");
            response.addHeader("WWW-Authenticate", "Bearer");
            return response;
        }

        try {
            if (uri.startsWith("/media/")) {
                return handleMediaRequest(session);
            } else if (uri.startsWith("/device/")) {
                return handleDeviceRequest(session);
            } else if (uri.equals("/settings")) {
                if (method.equals(Method.GET)) {
                    jsonResponse.put("success", true);
                    jsonResponse.put("settings", mSettingsParser.getSettings());
                } else if (method.equals(Method.POST)) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    String postData = files.get("postData");
                    if (postData == null || postData.isEmpty()) return badRequest("Missing JSON body (send Content-Type: application/json)");
                    JSONObject jsonObject = new JSONObject(postData);

                    if (getApiToken().isEmpty()) {
                        List<String> rejected = new ArrayList<>();
                        for (Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
                            String key = it.next();
                            if (PROTECTED_SETTINGS.contains(key) && !mSettingsParser.isUnchanged(key, jsonObject.get(key))) {
                                rejected.add(key);
                            }
                        }
                        if (!rejected.isEmpty()) {
                            return errorResponse(Response.Status.FORBIDDEN, "Changing " + String.join(", ", rejected)
                                    + " over HTTP requires an API token. Set httpApiToken via adb first (see README).");
                        }
                    }

                    try {
                        mSettingsParser.setSettings(jsonObject);
                    } catch (IllegalArgumentException e) {
                        return badRequest("Invalid settings: " + e.getMessage());
                    }

                    // Notify components of settings change (triggers MQTT reconnect, etc.)
                    Intent settingsIntent = new Intent(INTENT_SETTINGS_CHANGED);
                    LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(settingsIntent);

                    jsonResponse.put("success", true);
                    jsonResponse.put("settings", mSettingsParser.getSettings());
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString());
            } else if (uri.equals("/")) {
                JSONObject json = new JSONObject();

                try {
                    json.put("name", mApplicationContext.getPackageName());

                    String version = "unknown";
                    try {
                        PackageInfo pInfo = mApplicationContext.getPackageManager()
                                .getPackageInfo(mApplicationContext.getPackageName(), 0);
                        version = pInfo.versionName;
                    } catch (PackageManager.NameNotFoundException ignored) {}

                    json.put("version", version);
                    var device = DeviceModel.getReportedDevice();
                    json.put("modelName", device.name());
                    json.put("proximity", device.hasProximitySensor ? "true" : "false");
                    json.put("numOfButtons", device.buttons);
                    json.put("numOfInputs", device.inputs);
                } catch (JSONException e) {
                    Log.e("MQTT", "Error publishing hello", e);
                }

                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
            }
        } catch (JSONException e) {
            Log.e("HttpServer", "Invalid JSON in request", e);
            return badRequest("Invalid JSON: " + e.getMessage());
        } catch (ResponseException | IOException e) {
            Log.e("HttpServer", "Error handling request", e);
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", jsonResponse.toString());
    }

    private Response handleMediaRequest(IHTTPSession session) throws JSONException, ResponseException, IOException {
        Method method = session.getMethod();
        String uri = session.getUri();
        JSONObject jsonResponse = new JSONObject();

        switch (uri.replace("/media/", "")) {
            case "play":
                if (method.equals(Method.POST)) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    String postData = files.get("postData");
                    if (postData == null || postData.isEmpty()) return badRequest("Missing JSON body");
                    JSONObject jsonObject = new JSONObject(postData);

                    Uri mediaUri = Uri.parse(jsonObject.getString("url"));
                    boolean music = jsonObject.getBoolean("music");
                    double volume = jsonObject.getDouble("volume");

                    mMediaHelper.setVolume(volume);

                    if (music) {
                        mMediaHelper.playMusic(mediaUri);
                    } else {
                        mMediaHelper.playEffect(mediaUri);
                    }

                    jsonResponse.put("success", true);
                    jsonResponse.put("url", jsonObject.getString("url"));
                    jsonResponse.put("music", music);
                    jsonResponse.put("volume", volume);
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "pause":
                if (method.equals(Method.POST)) {
                    mMediaHelper.pauseMusic();
                    jsonResponse.put("success", true);
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "resume":
                if (method.equals(Method.POST)) {
                    mMediaHelper.resumeMusic();
                    jsonResponse.put("success", true);
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "stop":
                if (method.equals(Method.POST)) {
                    mMediaHelper.stopAll();
                    jsonResponse.put("success", true);
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "volume":
                if (method.equals(Method.POST)) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    String postData = files.get("postData");
                    if (postData == null || postData.isEmpty()) return badRequest("Missing JSON body");
                    JSONObject jsonObject = new JSONObject(postData);

                    double volume = jsonObject.getDouble("volume");

                    mMediaHelper.setVolume(volume);

                    jsonResponse.put("success", true);
                    jsonResponse.put("volume", mMediaHelper.getVolume());
                } else if (method.equals(Method.GET)) {
                    jsonResponse.put("success", true);
                    jsonResponse.put("volume", mMediaHelper.getVolume());
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            default:
                jsonResponse.put("success", false);
                jsonResponse.put("error", "Invalid request URI");
                break;
        }

        return newFixedLengthResponse(jsonResponse.getBoolean("success") ? Response.Status.OK : Response.Status.INTERNAL_ERROR, "application/json", jsonResponse.toString());
    }

    private Response handleDeviceRequest(IHTTPSession session) throws JSONException, ResponseException, IOException {
        Method method = session.getMethod();
        String uri = session.getUri();
        JSONObject jsonResponse = new JSONObject();

        // Parse query parameters. The body can only be read once, so keep it for the POST handlers.
        Map<String, String> params = new HashMap<>();
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
            params.putAll(session.getParms());
        } catch (IOException | ResponseException e) {
            Log.e("HttpServer", "Invalid parameters", e);
        }

        DeviceModel device = DeviceModel.getReportedDevice();

        switch (uri.replace("/device/", "")) {
            case "relay":
                if (method.equals(Method.GET)) {
                    var num = GetNumParameter(params, 0);
                    if (num == -999) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid num");
                    jsonResponse.put("success", true);
                    jsonResponse.put("state", mDeviceHelper.getRelay(num));
                } else if (method.equals(Method.POST)) {
                    var num = GetNumParameter(params, -1);
                    if (num == -999) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid num");

                    String postData = files.get("postData");
                    if (postData == null || postData.isEmpty()) return badRequest("Missing JSON body");
                    JSONObject jsonObject = new JSONObject(postData);

                    // num as json body
                    if (num == -1)
                        num = jsonObject.optInt("num", 0);
                    if (!jsonObject.has("state")) return badRequest("Missing state");

                    mDeviceHelper.setRelay(num, jsonObject.getBoolean("state"));

                    jsonResponse.put("success", true);
                    jsonResponse.put("state", mDeviceHelper.getRelay(num));
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "getTemperature":
                if (method.equals(Method.GET)) {
                    jsonResponse.put("success", true);
                    jsonResponse.put("temperature", mDeviceHelper.getTemperature());
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "getHumidity":
                if (method.equals(Method.GET)) {
                    jsonResponse.put("success", true);
                    jsonResponse.put("humidity", mDeviceHelper.getHumidity());
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "getLux":
                if (method.equals(Method.GET)) {
                    jsonResponse.put("success", true);
                    jsonResponse.put("lux", mDeviceSensorManager.getLastMeasuredLux());
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "getProximity":
                if (method.equals(Method.GET)) {

                    if (device.hasProximitySensor) {
                        jsonResponse.put("success", true);
                        jsonResponse.put("distance", mDeviceSensorManager.getLastMeasuredDistance());
                    } else {
                        jsonResponse.put("success", false);
                        jsonResponse.put("error", "This device doesn't support proximity sensor measurement");
                    }
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "wake":
                // Accept both GET and POST for convenience
                Log.i("HttpServer", "Wake request received");
                LocalBroadcastManager.getInstance(mApplicationContext)
                        .sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STOPPED));
                jsonResponse.put("success", true);
                break;
            case "sleep":
                // Accept both GET and POST for convenience
                Log.i("HttpServer", "Sleep request received");
                LocalBroadcastManager.getInstance(mApplicationContext)
                        .sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STARTED));
                jsonResponse.put("success", true);
                break;
            case "reboot":
                jsonResponse.put("success", false);
                if (method.equals(Method.POST)) {
                    long deltaTime = System.currentTimeMillis() - ShellyElevateApplication.getApplicationStartTime();
                    deltaTime /= 1000;
                    if (deltaTime > 20) {
                        try {
                            Runtime.getRuntime().exec("reboot");
                            jsonResponse.put("success", true);
                        } catch (IOException e) {
                            Log.e("HttpServer", "Error rebooting:", e);
                        }
                    } else {
                        // NanoHTTPD threads have no Looper, Toasts must run on the main thread
                        String waitMessage = "Please wait %s seconds before rebooting".replace("%s", String.valueOf(20 - deltaTime));
                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(mApplicationContext, waitMessage, Toast.LENGTH_LONG).show());
                    }
                }
                break;
            case "launchApp":
                if (method.equals(Method.POST)) {
                    String postData = files.get("postData");
                    if (postData != null) {
                        JSONObject jsonObject = new JSONObject(postData);
                        String packageName = jsonObject.optString("package", "io.homeassistant.companion.android.minimal");
                        try {
                            Intent launchIntent = ServiceHelper.watchdogLaunchIntent(mApplicationContext, packageName);
                            if (launchIntent != null) {
                                mApplicationContext.startActivity(launchIntent);
                                jsonResponse.put("success", true);
                                jsonResponse.put("package", packageName);
                            } else {
                                jsonResponse.put("success", false);
                                jsonResponse.put("error", "App not installed: " + packageName);
                            }
                        } catch (Exception e) {
                            jsonResponse.put("success", false);
                            jsonResponse.put("error", "Failed to launch: " + e.getMessage());
                        }
                    } else {
                        jsonResponse.put("success", false);
                        jsonResponse.put("error", "Missing package parameter");
                    }
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            default:
                jsonResponse.put("success", false);
                jsonResponse.put("error", "Invalid request URI");
                break;
        }

        return newFixedLengthResponse(jsonResponse.getBoolean("success") ? Response.Status.OK : Response.Status.INTERNAL_ERROR, "application/json", jsonResponse.toString());
    }

    private static int GetNumParameter(Map<String, String> params, int defaultValue) {
        // Get ?num=1
        String numParam = params.get("num");
        if (numParam != null) {
            try {
                return Integer.parseInt(numParam);
            } catch (NumberFormatException e) {
                // handle invalid number
                return -999;
            }
        }
        return defaultValue; // Default
    }

    public void onDestroy() {
        LocalBroadcastManager.getInstance(mApplicationContext).unregisterReceiver(settingsChangedBroadcastReceiver);
        closeAllConnections();
        stop();
    }
}
