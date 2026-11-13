package com.stretter.shellyelevateservice;

public class Constants {
    public static final String SHARED_PREFERENCES_NAME = "ShellyElevateService";

    //Media SP keys
    public static final String SP_MEDIA_ENABLED = "mediaEnabled";

    //HTTP Server SP Keys
    public static final String SP_HTTP_SERVER_ENABLED = "httpServer";
    public static final String SP_HTTP_API_TOKEN = "httpApiToken";

    //API token Intent (adb only, see ApiTokenReceiver)
    public static final String INTENT_SET_API_TOKEN = "com.stretter.shellyelevateservice.SET_API_TOKEN";
    public static final String INTENT_API_TOKEN_KEY = "token";
    public static final String INTENT_SET_SETTING = "com.stretter.shellyelevateservice.SET_SETTING";
    public static final String INTENT_SETTING_KEY = "key";
    public static final String INTENT_SETTING_VALUE = "value";

    //Screen SP Keys
    public static final String SP_AUTOMATIC_BRIGHTNESS = "automaticBrightness";
    public static final String SP_MIN_BRIGHTNESS = "minBrightness";
    public static final String SP_BRIGHTNESS = "brightness";

    //Screen Dimming SP Keys
    public static final String SP_SCREEN_SAVER_ENABLED = "screenSaver";
    public static final String SP_SCREEN_SAVER_DELAY = "screenSaverDelay";
    public static final String SP_WAKE_ON_PROXIMITY = "wakeOnProximity";
    public static final String SP_BLE_PROXY_ENABLED = "bleProxyEnabled";
    public static final String SP_DOORBELL_DETECT_ENABLED = "doorbellDetectEnabled";
    public static final String SP_DOORBELL_MIN_RATIO = "doorbellMinRatio";
    public static final String SP_DOORBELL_MIN_LEVEL = "doorbellMinLevel";
    /** Near threshold of a reflectance proximity sensor (NSPanel Pro); far is 3/4 of it. */
    public static final String SP_PROXIMITY_THRESHOLD = "proximityThreshold";
    public static final String SP_SCREEN_SAVER_MIN_BRIGHTNESS = "screenSaverMinBrightness";

    //MQTT SP Keys
    public static final String SP_MQTT_ENABLED = "mqttEnabled";
    public static final String SP_MQTT_BROKER = "mqttBroker";
    public static final String SP_MQTT_PORT = "mqttPort";
    public static final String SP_MQTT_USERNAME = "mqttUsername";
    public static final String SP_MQTT_PASSWORD = "mqttPassword";
    public static final String SP_MQTT_CLIENTID = "mqttDeviceId";

    //Device profile override (enum name of DeviceModel, empty/"auto" = detect by Build.MODEL)
    public static final String SP_DEVICE_MODEL = "deviceModel";

    //Debug SP Keys
    public static final String SP_DEBUG_KEYS = "debugKeys";

    //App Watchdog SP Keys
    public static final String SP_WATCHDOG_ENABLED = "watchdogEnabled";
    public static final String SP_WATCHDOG_PACKAGE = "watchdogPackage";
    public static final String SP_WATCHDOG_INTERVAL = "watchdogInterval";

    //Screen Dimming Intents
    public static final String INTENT_SCREEN_SAVER_STARTED = "com.stretter.shellyelevateservice.SCREEN_SAVER_STARTED";
    public static final String INTENT_SCREEN_SAVER_STOPPED = "com.stretter.shellyelevateservice.SCREEN_SAVER_STOPPED";

    //Sensor Intents
    public static final String INTENT_LIGHT_UPDATED = "com.stretter.shellyelevateservice.LIGHT_UPDATED";
    public static final String INTENT_LIGHT_KEY = "lightValue";
    public static final String INTENT_PROXIMITY_UPDATED = "com.stretter.shellyelevateservice.PROXIMITY_UPDATED";
    public static final String INTENT_PROXIMITY_KEY = "proximityValue";

    //Brightness control Intents (from MQTT)
    public static final String INTENT_SET_BRIGHTNESS = "com.stretter.shellyelevateservice.SET_BRIGHTNESS";
    public static final String INTENT_BRIGHTNESS_KEY = "brightness";
    public static final String INTENT_SET_AUTO_BRIGHTNESS = "com.stretter.shellyelevateservice.SET_AUTO_BRIGHTNESS";
    public static final String INTENT_AUTO_BRIGHTNESS_KEY = "automaticBrightness";

    //Settings Intent
    public static final String INTENT_SETTINGS_CHANGED = "com.stretter.shellyelevateservice.SETTINGS_CHANGED";

    //MQTT Topics
    public static final String MQTT_TOPIC_CONFIG_DEVICE = "homeassistant/device/%s/config";
    public static final String MQTT_TOPIC_STATUS = "shellyelevateservice/%s/status";
    public static final String MQTT_TOPIC_TEMP_SENSOR = "shellyelevateservice/%s/temp";
    public static final String MQTT_TOPIC_HUM_SENSOR = "shellyelevateservice/%s/hum";
    public static final String MQTT_TOPIC_LUX_SENSOR = "shellyelevateservice/%s/lux";
    public static final String MQTT_TOPIC_SCREEN_BRIGHTNESS = "shellyelevateservice/%s/bri";
    public static final String MQTT_TOPIC_SCREEN_BRIGHTNESS_COMMAND = "shellyelevateservice/%s/bri_set";
    public static final String MQTT_TOPIC_AUTO_BRIGHTNESS_STATE = "shellyelevateservice/%s/auto_bri";
    public static final String MQTT_TOPIC_PROXIMITY_THRESHOLD = "shellyelevateservice/%s/proximity_threshold";
    public static final String MQTT_TOPIC_BLE_PROXY_STATE = "shellyelevateservice/%s/ble_proxy";
    public static final String MQTT_TOPIC_BLE_PROXY_COMMAND = "shellyelevateservice/%s/ble_proxy_set";
    public static final String MQTT_TOPIC_DOORBELL_DETECT_STATE = "shellyelevateservice/%s/doorbell_detect";
    public static final String MQTT_TOPIC_DOORBELL_DETECT_COMMAND = "shellyelevateservice/%s/doorbell_detect_set";
    public static final String MQTT_TOPIC_DOORBELL_SOUND = "shellyelevateservice/%s/doorbell_sound";
    public static final String MQTT_TOPIC_PROXIMITY_THRESHOLD_COMMAND = "shellyelevateservice/%s/proximity_threshold_set";
    public static final String MQTT_TOPIC_AUTO_BRIGHTNESS_COMMAND = "shellyelevateservice/%s/auto_bri_set";
    public static final String MQTT_TOPIC_PROXIMITY_SENSOR = "shellyelevateservice/%s/proximity";
    public static final String MQTT_TOPIC_PRESENCE = "shellyelevateservice/%s/presence";
    public static final String MQTT_TOPIC_RELAY_STATE = "shellyelevateservice/%s/relay_state";
    public static final String MQTT_TOPIC_RELAY_COMMAND = "shellyelevateservice/%s/relay_command";
    public static final String MQTT_TOPIC_UPDATE = "shellyelevateservice/%s/update";
    public static final String MQTT_TOPIC_HELLO = "shellyelevateservice/%s/hello";
    public static final String MQTT_TOPIC_BUTTON_EVENT = "shellyelevateservice/%s/button_event";
    public static final String MQTT_TOPIC_SLEEP_BUTTON = "shellyelevateservice/%s/sleep";
    public static final String MQTT_TOPIC_WAKE_BUTTON = "shellyelevateservice/%s/wake";
    public static final String MQTT_TOPIC_REBOOT_BUTTON = "shellyelevateservice/%s/reboot";
    public static final String MQTT_TOPIC_RESTART_APP_BUTTON = "shellyelevateservice/%s/restart_app";

    //Restart App Intent
    public static final String INTENT_RESTART_APP = "com.stretter.shellyelevateservice.RESTART_APP";
    public static final String MQTT_TOPIC_SLEEPING_BINARY_SENSOR = "shellyelevateservice/%s/sleeping";
    public static final String MQTT_TOPIC_HOME_ASSISTANT_STATUS = "homeassistant/status";
    public static final String MQTT_TOPIC_UNKNOWN_KEY = "shellyelevateservice/%s/unknown_key";
}
