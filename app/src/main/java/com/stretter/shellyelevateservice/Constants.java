package com.stretter.shellyelevateservice;

public class Constants {
    public static final String SHARED_PREFERENCES_NAME = "ShellyElevateService";

    //Media SP keys
    public static final String SP_MEDIA_ENABLED = "mediaEnabled";

    //HTTP Server SP Keys
    public static final String SP_HTTP_SERVER_ENABLED = "httpServer";

    //Screen SP Keys
    public static final String SP_AUTOMATIC_BRIGHTNESS = "automaticBrightness";
    public static final String SP_MIN_BRIGHTNESS = "minBrightness";
    public static final String SP_BRIGHTNESS = "brightness";

    //Screen Dimming SP Keys
    public static final String SP_SCREEN_SAVER_ENABLED = "screenSaver";
    public static final String SP_SCREEN_SAVER_DELAY = "screenSaverDelay";
    public static final String SP_WAKE_ON_PROXIMITY = "wakeOnProximity";
    public static final String SP_SCREEN_SAVER_MIN_BRIGHTNESS = "screenSaverMinBrightness";

    //MQTT SP Keys
    public static final String SP_MQTT_ENABLED = "mqttEnabled";
    public static final String SP_MQTT_BROKER = "mqttBroker";
    public static final String SP_MQTT_PORT = "mqttPort";
    public static final String SP_MQTT_USERNAME = "mqttUsername";
    public static final String SP_MQTT_PASSWORD = "mqttPassword";
    public static final String SP_MQTT_CLIENTID = "mqttDeviceId";

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

    //Settings Intent
    public static final String INTENT_SETTINGS_CHANGED = "com.stretter.shellyelevateservice.SETTINGS_CHANGED";

    //MQTT Topics
    public static final String MQTT_TOPIC_CONFIG_DEVICE = "homeassistant/device/%s/config";
    public static final String MQTT_TOPIC_STATUS = "shellyelevateservice/%s/status";
    public static final String MQTT_TOPIC_TEMP_SENSOR = "shellyelevateservice/%s/temp";
    public static final String MQTT_TOPIC_HUM_SENSOR = "shellyelevateservice/%s/hum";
    public static final String MQTT_TOPIC_LUX_SENSOR = "shellyelevateservice/%s/lux";
    public static final String MQTT_TOPIC_SCREEN_BRIGHTNESS = "shellyelevateservice/%s/bri";
    public static final String MQTT_TOPIC_PROXIMITY_SENSOR = "shellyelevateservice/%s/proximity";
    public static final String MQTT_TOPIC_RELAY_STATE = "shellyelevateservice/%s/relay_state";
    public static final String MQTT_TOPIC_RELAY_COMMAND = "shellyelevateservice/%s/relay_command";
    public static final String MQTT_TOPIC_UPDATE = "shellyelevateservice/%s/update";
    public static final String MQTT_TOPIC_HELLO = "shellyelevateservice/%s/hello";
    public static final String MQTT_TOPIC_BUTTON_EVENT = "shellyelevateservice/%s/button_event";
    public static final String MQTT_TOPIC_SLEEP_BUTTON = "shellyelevateservice/%s/sleep";
    public static final String MQTT_TOPIC_WAKE_BUTTON = "shellyelevateservice/%s/wake";
    public static final String MQTT_TOPIC_REBOOT_BUTTON = "shellyelevateservice/%s/reboot";
    public static final String MQTT_TOPIC_SLEEPING_BINARY_SENSOR = "shellyelevateservice/%s/sleeping";
    public static final String MQTT_TOPIC_HOME_ASSISTANT_STATUS = "homeassistant/status";
    public static final String MQTT_TOPIC_UNKNOWN_KEY = "shellyelevateservice/%s/unknown_key";
}
