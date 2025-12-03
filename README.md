# ShellyElevate

> [!CAUTION]
> All content in this repository is provided "as is" and may render your device unusable. Always exercise caution when working with your device. No warranty or guarantee is provided.

ShellyElevate is a **background service** for the **Shelly Wall Display XL** that exposes hardware features to Home Assistant while letting you use the **official Home Assistant Companion app** as your dashboard.

## Why?

The Shelly Wall Display XL has great hardware (temperature/humidity sensors, light sensor, proximity sensor, 4 hardware buttons, relays) but no native way to expose these to Home Assistant when using the official HA app. ShellyElevate runs as a background service and:

- Publishes all sensors and button events to Home Assistant via **MQTT**
- Provides **screen dimming** with idle timeout (acts as a screensaver)
- Includes an **app watchdog** to keep Home Assistant running in the foreground
- Exposes an **HTTP API** for configuration and control
- Handles **hardware button events** (4 buttons on the XL)

## Features

| Feature | Description |
|---------|-------------|
| **MQTT Integration** | Auto-discovery for Home Assistant with sensors, buttons, relays, and events |
| **Hardware Buttons** | Publish button presses as events you can use in automations |
| **Screen Dimming** | Automatic dimming after idle timeout, wake on proximity/touch/button |
| **App Watchdog** | Automatically restart Home Assistant app if it crashes or closes |
| **Relay Control** | Control the built-in relays via MQTT or HTTP |
| **Sensor Publishing** | Temperature, humidity, light (lux), proximity, screen brightness |
| **HTTP API** | RESTful API for configuration and control |

## Installation

### 1. Enable Developer Mode on Shelly Wall Display XL

No jailbreak required! Just enable developer settings:

1. Navigate to **Settings → General → About Device**
2. On the About Device screen, you'll see **(H)ardware revision** and **(F)W version**
3. Tap them in this order: **F, H, F, F, H, F, H, H**
4. Developer mode is now enabled

### 2. Install via ADB

Connect a USB cable to the display and install the APK:

```bash
# Install ShellyElevate
adb install ./shellyelevatev2.apk

# Install Home Assistant Companion app
adb install ./home-assistant.apk
```

### 3. Configure via HTTP API

Once installed, configure the service via HTTP calls (see Configuration section below).

### 4. Reboot

```bash
adb reboot
```

After reboot, ShellyElevate will start automatically and launch the Home Assistant app (if watchdog is enabled).

## Configuration

All configuration is done via the HTTP API. The service runs on **port 8080**.

### MQTT Setup

```bash
curl -X POST http://<device-ip>:8080/settings \
  -H "Content-Type: application/json" \
  -d '{
    "mqttEnabled": true,
    "mqttBroker": "tcp://your-mqtt-broker",
    "mqttPort": 1883,
    "mqttUsername": "your-username",
    "mqttPassword": "your-password"
  }'
```

### App Watchdog

Keep the Home Assistant app running in the foreground:

```bash
curl -X POST http://<device-ip>:8080/settings \
  -H "Content-Type: application/json" \
  -d '{
    "watchdogEnabled": true,
    "watchdogPackage": "io.homeassistant.companion.android",
    "watchdogInterval": 10
  }'
```

### Screen Dimming

```bash
curl -X POST http://<device-ip>:8080/settings \
  -H "Content-Type: application/json" \
  -d '{
    "screenSaver": true,
    "screenSaverDelay": 45,
    "screenSaverMinBrightness": 10,
    "wakeOnProximity": true
  }'
```

## HTTP API Endpoints

### Device Info
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | Device info (name, version, model) |
| GET | `/settings` | Get all settings |
| POST | `/settings` | Update settings |

### Device Control
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/device/relay?num=0` | Get relay state |
| POST | `/device/relay` | Set relay state `{"num": 0, "state": true}` |
| GET | `/device/getTemperature` | Get temperature |
| GET | `/device/getHumidity` | Get humidity |
| GET | `/device/getLux` | Get light level |
| GET | `/device/getProximity` | Get proximity distance |
| GET/POST | `/device/wake` | Wake screen |
| GET/POST | `/device/sleep` | Dim screen |
| POST | `/device/reboot` | Reboot device |
| POST | `/device/launchApp` | Launch app `{"package": "..."}` |

### Media Control
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/media/play` | Play audio `{"url": "...", "music": true, "volume": 0.5}` |
| POST | `/media/pause` | Pause music |
| POST | `/media/resume` | Resume music |
| POST | `/media/stop` | Stop all audio |
| GET/POST | `/media/volume` | Get/set volume |

## MQTT Topics

All topics are prefixed with `shellyelevatev2/<client-id>/`.

### Sensors (published automatically)
| Topic | Description |
|-------|-------------|
| `temp` | Temperature in °C |
| `hum` | Humidity in % |
| `lux` | Light level in lux |
| `proximity` | Proximity distance in cm |
| `bri` | Screen brightness (0-255) |
| `sleeping` | Screen dimmed state (ON/OFF) |
| `relay_state` | Relay state (ON/OFF) |

### Events (for automations)
| Topic | Payload | Description |
|-------|---------|-------------|
| `button_event/0` | `{"event_type": "press"}` | Button 0 pressed |
| `button_event/1` | `{"event_type": "press"}` | Button 1 pressed |
| `button_event/2` | `{"event_type": "press"}` | Button 2 pressed |
| `button_event/3` | `{"event_type": "press"}` | Button 3 pressed |

### Commands (subscribe)
| Topic | Payload | Description |
|-------|---------|-------------|
| `relay_command` | `ON` / `OFF` | Control relay |
| `sleep` | any | Dim screen |
| `wake` | any | Wake screen |
| `reboot` | any | Reboot device |

## Home Assistant Auto-Discovery

ShellyElevate publishes MQTT discovery config to `homeassistant/device/<client-id>/config`. After connecting to MQTT, the device will automatically appear in Home Assistant with:

- Temperature sensor
- Humidity sensor
- Light sensor
- Proximity sensor (if available)
- Screen brightness sensor
- Sleeping binary sensor
- Relay switch(es)
- Button events (for automations)
- Sleep/Wake/Reboot buttons

## Settings Reference

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `mqttEnabled` | boolean | `false` | Enable MQTT |
| `mqttBroker` | string | `""` | MQTT broker URL (e.g., `tcp://192.168.1.100`) |
| `mqttPort` | int | `1883` | MQTT port |
| `mqttUsername` | string | `""` | MQTT username |
| `mqttPassword` | string | `""` | MQTT password |
| `mqttDeviceId` | string | auto | MQTT client ID |
| `screenSaver` | boolean | `true` | Enable screen dimming |
| `screenSaverDelay` | int | `45` | Seconds before dimming |
| `screenSaverMinBrightness` | int | `10` | Minimum brightness when dimmed |
| `wakeOnProximity` | boolean | `false` | Wake screen on proximity |
| `automaticBrightness` | boolean | `true` | Auto-adjust brightness based on light |
| `minBrightness` | int | `48` | Minimum auto brightness |
| `watchdogEnabled` | boolean | `false` | Enable app watchdog |
| `watchdogPackage` | string | `io.homeassistant.companion.android` | App to watch |
| `watchdogInterval` | int | `10` | Watchdog check interval (seconds) |
| `httpServer` | boolean | `true` | Enable HTTP server |
| `debugKeys` | boolean | `false` | Log all key events to MQTT |

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```

## Contributing

If you'd like to contribute or have a feature request, please create a pull request or open an issue.

## Credits

Originally based on [ShellyElevate by RapierXbox](https://github.com/RapierXbox/ShellyElevate).
