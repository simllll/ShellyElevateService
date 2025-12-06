# ShellyElevateService

> [!CAUTION]
> All content in this repository is provided "as is" and may render your device unusable. Always exercise caution when working with your device. No warranty or guarantee is provided.

ShellyElevate is a **background service** for the **Shelly Wall Display XL** that exposes hardware features to Home Assistant while letting you use the **official Home Assistant Companion app** as your dashboard.

## Why?

The built-in WebView of the Shelly Wall Display is **slow and buggy**. It kept crashing every few hours (sometimes days, mostly hours), and was laggy and unresponsive. With the **native Home Assistant Companion app**, all these problems disappeared - it's fast, stable, and just works.

However, switching to a third-party app means you lose access to the great hardware features: temperature/humidity sensors, light sensor, proximity sensor, 4 hardware buttons, and relays. There's no native way to expose these to Home Assistant when using the official HA app (or any other dashboard app like Fully Kiosk, WallPanel, etc.).

**That's why this background service exists** - it lets you use whatever app you like for your dashboard while still having full access to all hardware features. ShellyElevate runs silently in the background and:

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
# Install ShellyElevate Service
adb install ./shellyelevateservice.apk

# Install Home Assistant Companion app (minimal version)
adb install ./home-assistant-minimal.apk
```

### 3. Grant Permissions

The service requires the **Write Settings** permission to control screen brightness. This must be granted manually:

```bash
adb shell appops set com.stretter.shellyelevateservice WRITE_SETTINGS allow
```

Optionally, whitelist the app from battery optimization to prevent Android from killing the service:

```bash
adb shell dumpsys deviceidle whitelist +com.stretter.shellyelevateservice
```

### 4. Start the Service

Start the service manually (it will auto-start on subsequent boots):

```bash
adb shell am start-foreground-service com.stretter.shellyelevateservice/.ShellyElevateService
```

To verify it's running:

```bash
adb shell dumpsys activity services com.stretter.shellyelevateservice
```

### 5. Configure via HTTP API

Once installed, configure the service via HTTP calls (see Configuration section below).

### 6. Reboot (Optional)

```bash
adb reboot
```

After reboot, ShellyElevate will start automatically and launch the Home Assistant app (if watchdog is enabled).

## Recommended Setup

This is the setup I use on my Shelly Wall Display XL:

1. **Ultra Small Launcher** - Replace Shelly's default launcher so their app doesn't start automatically
   - Download: [ultra-small-launcher.apk](https://blakadder.com/assets/files/ultra-small-launcher.apk)
   - Install via `adb install ultra-small-launcher.apk`
   - Set as default launcher when prompted (or via Settings → Apps → Default Apps)
   - Tip: Open the launcher anytime with `adb shell input keyevent 3` (HOME key)

2. **Home Assistant Companion (Minimal)** - Lightweight version of the HA app
   - Download from: [Home Assistant Android Releases](https://github.com/home-assistant/android/releases)
   - Look for `home-assistant-android-minimal-*.apk`
   - This is the default app monitored by the watchdog

3. **ShellyElevateService** - This service running in the background

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
    "watchdogPackage": "io.homeassistant.companion.android.minimal",
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

All topics are prefixed with `shellyelevateservice/<client-id>/`.

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
| `button_event/1` | `{"event_type": "single"}` | Button 1 single press |
| `button_event/1` | `{"event_type": "double"}` | Button 1 double press |
| `button_event/1` | `{"event_type": "long"}` | Button 1 long press (held 500ms+) |
| `button_event/2` | `{"event_type": "..."}` | Button 2 events |
| `button_event/3` | `{"event_type": "..."}` | Button 3 events |
| `button_event/4` | `{"event_type": "..."}` | Button 4 events |

**Event types:**
- `single` - Quick press and release
- `double` - Two presses within 300ms
- `long` - Button held for 500ms or more

### Commands (subscribe)
| Topic | Payload | Description |
|-------|---------|-------------|
| `relay_command` | `ON` / `OFF` | Control relay |
| `sleep` | any | Dim screen |
| `wake` | any | Wake screen |
| `reboot` | any | Reboot device |
| `restart_app` | any | Force kill and restart the watchdog app |

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
- Sleep/Wake/Reboot/Restart App buttons

## Home Assistant Automation Examples

### Single Press - Toggle Light

```yaml
automation:
  - alias: "Button 1 Single Press - Toggle Kitchen Light"
    trigger:
      - platform: state
        entity_id: event.shelly_wall_display_button_1
        attribute: event_type
        to: "single"
    action:
      - service: light.toggle
        target:
          entity_id: light.kitchen
```

### Double Press - Set Scene

```yaml
automation:
  - alias: "Button 1 Double Press - Movie Mode"
    trigger:
      - platform: state
        entity_id: event.shelly_wall_display_button_1
        attribute: event_type
        to: "double"
    action:
      - service: scene.turn_on
        target:
          entity_id: scene.movie_mode
```

### Long Press - Turn Off All Lights

```yaml
automation:
  - alias: "Button 1 Long Press - All Lights Off"
    trigger:
      - platform: state
        entity_id: event.shelly_wall_display_button_1
        attribute: event_type
        to: "long"
    action:
      - service: light.turn_off
        target:
          entity_id: all
```

### Handle Multiple Event Types

```yaml
automation:
  - alias: "Wall Display Button 1 - Multi-Action"
    trigger:
      - platform: state
        entity_id: event.shelly_wall_display_button_1
    action:
      - choose:
          - conditions:
              - condition: template
                value_template: "{{ trigger.to_state.attributes.event_type == 'single' }}"
            sequence:
              - service: light.toggle
                target:
                  entity_id: light.kitchen
          - conditions:
              - condition: template
                value_template: "{{ trigger.to_state.attributes.event_type == 'double' }}"
            sequence:
              - service: light.turn_on
                target:
                  entity_id: light.kitchen
                data:
                  brightness_pct: 100
          - conditions:
              - condition: template
                value_template: "{{ trigger.to_state.attributes.event_type == 'long' }}"
            sequence:
              - service: light.turn_off
                target:
                  entity_id: light.kitchen
```

## Settings Reference

All settings can be configured via `POST /settings` with a JSON body.

### MQTT Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `mqttEnabled` | boolean | `false` | Enable MQTT connection |
| `mqttBroker` | string | `""` | MQTT broker URL (e.g., `tcp://192.168.1.100`) |
| `mqttPort` | int | `1883` | MQTT broker port |
| `mqttUsername` | string | `""` | MQTT username |
| `mqttPassword` | string | `""` | MQTT password |
| `mqttDeviceId` | string | auto-generated | MQTT client ID (used in topics) |

### Screen & Brightness Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `automaticBrightness` | boolean | `true` | Auto-adjust brightness based on ambient light |
| `brightness` | int | `255` | Manual brightness level (0-255, used when auto is off) |
| `minBrightness` | int | `48` | Minimum brightness for auto-brightness |

### Screen Dimming (Screensaver) Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `screenSaver` | boolean | `true` | Enable screen dimming on idle |
| `screenSaverDelay` | int | `45` | Seconds of inactivity before dimming |
| `screenSaverMinBrightness` | int | `10` | Brightness level when dimmed (0-255) |
| `wakeOnProximity` | boolean | `false` | Wake screen when proximity sensor triggered |

### App Watchdog Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `watchdogEnabled` | boolean | `false` | Enable app watchdog |
| `watchdogPackage` | string | `io.homeassistant.companion.android.minimal` | Package name of app to keep running |
| `watchdogInterval` | int | `10` | Check interval in seconds |

### Other Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `httpServer` | boolean | `true` | Enable HTTP API server on port 8080 |
| `mediaEnabled` | boolean | `true` | Enable media/audio playback |
| `debugKeys` | boolean | `false` | Publish unknown key codes to MQTT for debugging |

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
