# ShellyElevateService

> [!CAUTION]
> All content in this repository is provided "as is" and may render your device unusable. Always exercise caution when working with your device. No warranty or guarantee is provided.

ShellyElevate is a **background service** for the **Shelly Wall Display XL** (and the **Sonoff NSPanel Pro**, see [Supported devices](#supported-devices)) that exposes hardware features to Home Assistant while letting you use the **official Home Assistant Companion app** (or the built-in native dashboard) as your dashboard.

## Why?

The built-in WebView of the Shelly Wall Display is **slow and buggy**. It kept crashing every few hours (sometimes days, mostly hours), and was laggy and unresponsive. With the **native Home Assistant Companion app**, all these problems disappeared - it's fast, stable, and just works.

However, switching to a third-party app means you lose access to the great hardware features: temperature/humidity sensors, light sensor, proximity sensor, 4 hardware buttons, and relays. There's no native way to expose these to Home Assistant when using the official HA app (or any other dashboard app like Fully Kiosk, WallPanel, etc.).

**That's why this background service exists** - it lets you use whatever app you like for your dashboard while still having full access to all hardware features. ShellyElevate runs silently in the background and:

- Publishes all sensors and button events to Home Assistant via **MQTT**
- Provides **screen dimming** with idle timeout (acts as a screensaver)
- Includes an **app watchdog** to keep Home Assistant running in the foreground
- Exposes an **HTTP API** for configuration and control
- Handles **hardware button events** (4 buttons on the XL)
- Optionally replaces the HA app with a **native dashboard** that renders your Lovelace dashboard
  without a WebView (see [Native dashboard](#native-dashboard))

## Features

| Feature | Description |
|---------|-------------|
| **MQTT Integration** | Auto-discovery for Home Assistant with sensors, buttons, relays, and events |
| **Hardware Buttons** | Publish button presses as events you can use in automations |
| **Screen Dimming** | Automatic dimming after idle timeout, wake on proximity/touch/button |
| **App Watchdog** | Automatically restart Home Assistant app if it crashes or closes |
| **Relay Control** | Control the built-in relays via MQTT or HTTP |
| **Sensor Publishing** | Temperature, humidity, light (lux), proximity, screen brightness |
| **HTTP API** | RESTful API for configuration and control, optional bearer token |
| **Presence** | Wide-range presence sensor (XL) or proximity (NSPanel Pro) as occupancy binary sensor, optional wake on approach |
| **Native dashboard** | Built-in Lovelace renderer (Kotlin/Compose, no WebView) with browser_mod emulation, see [`dashboard/README.md`](dashboard/README.md) |

## Supported devices

One APK runs on all of them; the device is detected from `Build.MODEL` and only the hardware it
really has is exposed (HTTP API, MQTT discovery).

| Device | `Build.MODEL` | Relays | Temp / Hum | Light | Proximity | Presence | Buttons | Backlight |
|--------|---------------|--------|------------|-------|-----------|----------|---------|-----------|
| Shelly Wall Display (`STARGATE`) | `Stargate` | 1 | yes | yes | – | – | – | sysfs / Settings |
| Shelly Wall Display 2 / X2 (`ATLANTIS`, `PEGASUS`) | `Atlantis`, `Pegasus` | 1 | yes | yes | yes | – | – | sysfs / Settings |
| Shelly Wall Display XL (`BLAKE`) | `Blake` | 1 | yes | yes | yes | wide-range sensor | 4 | sysfs / Settings |
| Sonoff NSPanel Pro 86 (`NSPANEL_PRO`) | `px30_evb` | 2 (GPIO 114/115) | – | yes | yes (reflectance) | from proximity | – | Settings API (min = sysfs 10), sleep = display off (screen-off timeout) |

Unknown models are treated as the original Shelly Wall Display. If detection picks the wrong
profile, set `deviceModel` to one of the names in brackets (`""`/`auto` = detect; needs an API
token, takes effect after a service restart).

## Installation

The steps below are for the Shelly Wall Display. For the NSPanel Pro see
[Sonoff NSPanel Pro](#sonoff-nspanel-pro) (steps 4–7 are the same).

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

Start the service manually (it will auto-start on subsequent boots and after app updates):

```bash
adb shell am start-foreground-service com.stretter.shellyelevateservice/.ShellyElevateService
```

To verify it's running:

```bash
adb shell dumpsys activity services com.stretter.shellyelevateservice
```

### 5. Set an HTTP API token

The HTTP API on port 8080 is reachable by everyone on your network. Settings that redirect the device
(`mqttBroker`, `mqttPort`, `mqttUsername`, `mqttDeviceId`, `watchdogPackage`, `deviceModel`) can **only** be changed
over HTTP when an API token is set. The token itself can only be set via adb:

```bash
adb shell am broadcast -n com.stretter.shellyelevateservice/.ApiTokenReceiver \
  -a com.stretter.shellyelevateservice.SET_API_TOKEN --es token 'choose-a-long-random-token'
```

(To remove the token again: same command with `--es token ''`.) The receiver is protected by
`android.permission.DUMP`, which only the adb shell holds, so other apps on the display cannot set it.

Once a token is set, **every** HTTP request must send it:

```bash
curl -H "Authorization: Bearer choose-a-long-random-token" http://<device-ip>:8080/settings
```

### 6. Configure via HTTP API

Once installed, configure the service via HTTP calls (see Configuration section below).

### 7. Reboot (Optional)

```bash
adb reboot
```

After reboot, ShellyElevate will start automatically and launch the Home Assistant app (if watchdog is enabled).

## Sonoff NSPanel Pro

ShellyElevate replaces the closed-source *NSPanel Pro Tools* app (`com.seaky.nspanelpro.tools`):
one app provides MQTT sensors, relays, sleep/wake, brightness, reboot, watchdog and (optionally) the
native dashboard.

The NSPanel Pro runs an Android 8.1 userdebug build (480×480 screen). Its backlight node
(`/sys/class/backlight/backlight/brightness`) is root-only and the shipped `/system/xbin/su` is
not setuid, so ShellyElevate works **without root**, with the same permissions NSPanel Pro Tools
had (`WRITE_SETTINGS`, `WAKE_LOCK`):

- **Brightness / dimming** go through the Android Settings API (Settings 0 = backlight 10).
- **Sleep** with `screenSaverMinBrightness` `0` (the NSPanel default) switches the display really
  off: the screen-off timeout is lowered to 1 s, Android turns the display off, and the original
  timeout is restored right after (also after a crash, on the next start). While the display is
  off a partial wake lock keeps MQTT and the sensors running, and the watchdog pauses.
  `screenSaverMinBrightness` > 0 dims instead.
- **Wake** (touch, proximity with `wakeOnProximity`, MQTT/HTTP) uses a short screen wake lock.
  Touches are read from the touchscreen node (`/dev/input/event1` is world-readable); a tap on the
  off display is dropped by Android, so it never reaches the dashboard.
- Light and proximity come from the Android sensors (their raw input nodes are not readable).
  The proximity sensor reports raw infrared **reflectance**, not a distance (higher = closer:
  ~20 at rest, 55–80 with a person standing in front of the panel, 100–6000 with a hand close to
  it; Android's `maxRange` of 9 is meaningless). *Near* is ≥ `proximityThreshold` (default 40, 25–500;
  also the *Proximity Threshold* number entity in HA) and *far* ≤ ¾ of it (hysteresis), which
  drives `presence` and `wakeOnProximity`. Watch the proximity sensor while standing in front of
  the panel to pick a value above the resting level (it may be higher in daylight). It only reacts to someone directly in front of the
  panel, not across a room. Its value is published at most every 5 s plus on every near/far change (the
  sensor itself reports several values per second), without a unit.
- **Relays**: relay 1 and 2 are GPIO 114 and 115 (`/sys/class/gpio/gpio11x/value`, writable by
  apps; the same nodes NSPanel Pro Tools drives). GPIO 113 is never touched.
- **Reboot** sets the `sys.powerctl` property, which init accepts from the app on this
  permissive-SELinux userdebug build; the panel is back after about 70 s.
- If the touchscreen node is not readable, a 1 px overlay detects touches instead (needs
  `SYSTEM_ALERT_WINDOW`). A setuid-root `su` (e.g. Magisk), if present, is used automatically for
  the backlight, `reboot` and `restart_app`. Without it, `restart_app` only works with the native
  dashboard as watchdog target (it restarts the `:dashboard` process); other apps cannot be
  force-stopped.

> [!WARNING]
> The stock firmware ships with `ro.adb.secure=0`: adb on port 5555 accepts **any** connection
> without an authorization prompt, so everyone on your network can control the panel. Keep it in
> a trusted network/VLAN.

### Setup

```bash
adb connect <nspanel-ip>:5555        # or USB

# 1. Stop NSPanel Pro Tools first: it also drives the backlight and would fight over it
adb shell am force-stop com.seaky.nspanelpro.tools
adb shell pm disable-user --user 0 com.seaky.nspanelpro.tools   # or: adb uninstall com.seaky.nspanelpro.tools

# 2. Install and grant
adb install ./shellyelevateservice.apk
adb shell appops set com.stretter.shellyelevateservice WRITE_SETTINGS allow
adb shell appops set com.stretter.shellyelevateservice SYSTEM_ALERT_WINDOW allow   # only needed if /dev/input/event1 is not readable
adb shell dumpsys deviceidle whitelist +com.stretter.shellyelevateservice

# 3. Android must not turn the screen off or change the brightness on its own
#    (ShellyElevate's idle timer decides; the sleep trick restores this value every time)
adb shell settings put system screen_off_timeout 2147483647
adb shell settings put system screen_brightness_mode 0

# 4. Start, then set the API token and configure as in steps 4–6 above
adb shell am start-foreground-service com.stretter.shellyelevateservice/.ShellyElevateService
```

`adb logcat -s ShellyElevateService ScreenOffTimeout InputEventReader DeviceHelper` shows sleep/wake
(`Switching screen off via screen-off timeout`, `Screen-off timeout restored to …`) and which input
devices are read.

### Migrating from NSPanel Pro Tools

NSPanel Pro Tools announced its own MQTT device. After switching, delete that device in Home
Assistant (Settings → Devices → MQTT) and update automations to the new entities
(`<id>` = `mqttDeviceId`, the entity ids follow the device name *Sonoff NSPanel Pro*):

| NSPanel Pro Tools | ShellyElevate |
|-------------------|---------------|
| light / lux sensor | `sensor.…_light` (topic `shellyelevateservice/<id>/lux`) |
| proximity sensor | `sensor.…_proximity` (reflectance) + `binary_sensor.…_presence` (near = ON) |
| `switch_1` / `switch_2` (relays) | `switch.…_relay` / `switch.…_relay_1` (topics `relay_command`, `relay_command_1`) |
| `wake_up` command | `button.…_wake` (or `POST /device/wake`) |
| `sleep` command | `button.…_sleep` (or `POST /device/sleep`) |
| screen on/off state | `binary_sensor.…_sleeping` |
| brightness | `number.…_screen_brightness` + `switch.…_auto_brightness` |
| `reboot` command | `button.…_reboot` |
| `default_app` / `launcher` | watchdog: `watchdogPackage` (`dashboard` = native dashboard) + `watchdogEnabled`; `button.…_restart_app` |
| `adb` command | not offered: enable ADB over network once via `adb tcpip 5555` |

There are no temperature, humidity or button entities on the NSPanel. The `adc-keys` input
device (ESC/HOME/VOLUME) is not wired on the 86 model; its key codes are only published with
`debugKeys` (topic `unknown_key`).

## Native dashboard

Instead of the HA Companion app, ShellyElevate can show your dashboard itself: a native
(Kotlin + Jetpack Compose) Lovelace renderer in its own process (`:dashboard`). It loads the
dashboard config live from Home Assistant and draws the cards natively in the HA default (dark)
theme, so popups and views open instantly even on the slow SoCs of these panels, and a dashboard
change in HA shows up without touching the device.

- Layouts: sections and masonry views, view badges; swipe navigation between views (live pager),
  back key, automatic return to the first view after inactivity.
- Core cards plus common HACS cards (bubble-card, button-card, auto-entities, Mushroom,
  template-entity-row, timer-bar, xiaomi-vacuum-map-card, fab-card, …); unknown card types show a
  placeholder.
- browser_mod emulation: registers as a browser_mod browser, so popups, notifications, navigate
  and the browser's media player (TTS) from existing automations keep working.
- Instant on-screen feedback for the hardware buttons.
- Guest lock: while an HA entity (default `binary_sensor.lock_dashboards`) is on, touches only show
  a lock screen and the display goes back to sleep after 10 s; automation popups (alarm keypad)
  stay usable. The dashboard asks the service to sleep through a not-exported broadcast receiver
  (`GuestLockContract.ACTION_SLEEP`, same path as MQTT `sleep`).

Setup, the full list of supported cards and the limitations are in
[`dashboard/README.md`](dashboard/README.md). Use `watchdogPackage` `dashboard` to keep it in
front ([Native dashboard as watchdog target](#native-dashboard-as-watchdog-target)).

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

Alternatively skip the HA app and use the [native dashboard](#native-dashboard).

## Configuration

All configuration is done via the HTTP API. The service runs on **port 8080**.
The examples assume an API token is set (see Installation step 5); without a token, drop the
`Authorization` header (the MQTT broker/user/device id and the watchdog package can then not be changed).

### MQTT Setup

```bash
curl -X POST http://<device-ip>:8080/settings \
  -H "Authorization: Bearer <token>" \
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
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "watchdogEnabled": true,
    "watchdogPackage": "io.homeassistant.companion.android.minimal",
    "watchdogInterval": 10
  }'
```

#### Native dashboard as watchdog target

Set `watchdogPackage` to `dashboard` (or the own package name `com.stretter.shellyelevateservice`)
to keep the built-in native dashboard (see [`dashboard/README.md`](dashboard/README.md)) in front
instead of the HA app. The watchdog then checks the `:dashboard` process and starts
`DashboardActivity` directly; `restart_app` kills only the dashboard process (never the service)
and relaunches it after 500 ms. Switching between HA app and dashboard works at runtime; the next
check runs immediately after the change.

Without an HTTP API token, the watchdog settings can be changed via adb (the receiver is protected
by `android.permission.DUMP` like the token receiver; only `watchdogPackage`, `watchdogEnabled`
and `watchdogInterval` are accepted):

```bash
# switch to the native dashboard
adb shell am broadcast -n com.stretter.shellyelevateservice/.AdbSettingReceiver \
  -a com.stretter.shellyelevateservice.SET_SETTING --es key watchdogPackage --es value dashboard

# back to the HA Companion app
adb shell am broadcast -n com.stretter.shellyelevateservice/.AdbSettingReceiver \
  -a com.stretter.shellyelevateservice.SET_SETTING --es key watchdogPackage \
  --es value io.homeassistant.companion.android.minimal

# enable/disable, interval in seconds
adb shell am broadcast -n com.stretter.shellyelevateservice/.AdbSettingReceiver \
  -a com.stretter.shellyelevateservice.SET_SETTING --es key watchdogEnabled --es value true
adb shell am broadcast -n com.stretter.shellyelevateservice/.AdbSettingReceiver \
  -a com.stretter.shellyelevateservice.SET_SETTING --es key watchdogInterval --es value 10
```

The broadcast result (`data="Set watchdogPackage = dashboard"` or `data="Rejected …"`) shows
whether the value was accepted.

### Screen Dimming

```bash
curl -X POST http://<device-ip>:8080/settings \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "screenSaver": true,
    "screenSaverDelay": 45,
    "screenSaverMinBrightness": 10,
    "wakeOnProximity": true
  }'
```

## HTTP API Endpoints

If `httpApiToken` is set, every endpoint requires `Authorization: Bearer <token>` (otherwise `401`).
`POST /settings` returns `403` for protected keys when no token is set, and `400` for invalid JSON
or values that do not match the setting's type (e.g. `"mqttPort": "abc"`; `"1883"` is accepted and
stored as a number). Nothing is stored if any key is rejected.

### Device Info
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | Device info (name, version, model) |
| GET | `/settings` | Get all settings |
| POST | `/settings` | Update settings |

### Device Control
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/device/relay?num=0` | Get relay state (`num` 0 = relay 1, 1 = relay 2) |
| POST | `/device/relay` | Set relay state `{"num": 0, "state": true}` |
| GET | `/device/getTemperature` | Get temperature |
| GET | `/device/getHumidity` | Get humidity |
| GET | `/device/getLux` | Get light level |
| GET | `/device/getProximity` | Get proximity value (distance in cm; reflectance on the NSPanel Pro) |
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

### Availability
| Topic | Description |
|-------|-------------|
| `status` | `online` (retained) while connected, `offline` as MQTT will (LWT) and on service stop. Used as availability topic for all entities. |

### Sensors (published automatically)
| Topic | Description |
|-------|-------------|
| `temp` | Temperature in °C (every 30 s) |
| `hum` | Humidity in % (every 30 s) |
| `lux` | Light level in lux |
| `proximity` | Proximity distance in cm (NSPanel Pro: reflectance, at most every 5 s) |
| `presence` | Presence sensor (ON = near / OFF = far): XL wide-range sensor, NSPanel Pro proximity |
| `bri` | Screen brightness (0-255) |
| `auto_bri` | Automatic brightness enabled (ON/OFF) |
| `sleeping` | Screen dimmed state (ON/OFF) |
| `relay_state`, `relay_state_1` | Relay 1 / relay 2 state (ON/OFF) |

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
| `relay_command`, `relay_command_1` | `ON` / `OFF` | Control relay 1 / relay 2 |
| `bri_set` | `0`-`255` | Set screen brightness (turns automatic brightness off) |
| `auto_bri_set` | `ON` / `OFF` | Turn automatic brightness on/off |
| `proximity_threshold_set` | `25`-`500` | Near threshold of the NSPanel Pro proximity sensor (state topic `proximity_threshold`) |
| `sleep` | any | Dim screen |
| `wake` | any | Wake screen |
| `reboot` | any | Reboot device |
| `restart_app` | any | Force kill and restart the watchdog app (native dashboard: only its process) |
| `update` | any | Re-publish discovery and all states |

Retained messages on command topics are ignored (a retained `reboot` can never cause a reboot loop).
The service also listens to `homeassistant/status` and re-publishes discovery and all states when
Home Assistant comes `online`.

## Home Assistant Auto-Discovery

ShellyElevate publishes MQTT discovery config to `homeassistant/device/<client-id>/config`. After connecting to MQTT, the device will automatically appear in Home Assistant with:

- Temperature sensor (if available)
- Humidity sensor (if available)
- Light sensor
- Proximity sensor (if available)
- Presence binary sensor (occupancy, if available)
- Screen Brightness number (slider 0-255)
- Auto Brightness switch
- Proximity Threshold number (NSPanel Pro)
- Sleeping binary sensor
- Relay switch(es) (if available)
- Button events (for automations)
- Sleep/Wake/Reboot/Restart App buttons

All entities use `shellyelevateservice/<client-id>/status` as availability topic, so they become
**unavailable** when the display is offline. Stopping the service only marks the device offline; it is
never removed from Home Assistant automatically (delete it in HA if you no longer need it).

Setting the brightness from Home Assistant switches automatic brightness off (so the value sticks);
turn the **Auto Brightness** switch back on to return to light-sensor control. With automatic
brightness on, the brightness is only changed when the computed value moves by at least 3 steps.

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
| `screenSaverMinBrightness` | int | `10` (NSPanel Pro: `0`) | Brightness level when dimmed (0-255; on the NSPanel Pro `0` switches the display off) |
| `wakeOnProximity` | boolean | `false` | Wake screen when the proximity/presence sensor reports "near" |

### App Watchdog Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `watchdogEnabled` | boolean | `false` | Enable app watchdog |
| `watchdogPackage` | string | `io.homeassistant.companion.android.minimal` | Package name of app to keep running (`dashboard` = native dashboard) |
| `watchdogInterval` | int | `10` | Check interval in seconds (changes apply from the next check) |

### Other Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `httpServer` | boolean | `true` | Enable HTTP API server on port 8080 |
| `httpApiToken` | string | `""` | Bearer token for the HTTP API (set via adb, see Installation step 5; masked in `GET /settings`) |
| `mediaEnabled` | boolean | `true` | Enable media/audio playback |
| `debugKeys` | boolean | `false` | Publish unknown key codes to MQTT for debugging |
| `deviceModel` | string | not set (detect) | Force a device profile, e.g. `NSPANEL_PRO` or `BLAKE` (see [Supported devices](#supported-devices); protected, applies after a service restart) |

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
