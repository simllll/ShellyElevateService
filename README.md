# PanelElevate

> [!CAUTION]
> All content in this repository is provided "as is" and may render your device unusable. Always exercise caution when working with your device. No warranty or guarantee is provided.

PanelElevate turns Android wall panels into first-class Home Assistant panels: the **Shelly Wall
Displays** (XL, 2, X2, …) and the **Sonoff NSPanel Pro**, without root. One app, two parts:

- a **native dashboard** that renders your Lovelace dashboard live from Home Assistant without a
  WebView (Kotlin + Jetpack Compose, HA look and feel, instant popups even on the slow panel SoCs),
  including browser_mod emulation, WebRTC camera live view and a guest lock;
- a **background service** that exposes the panel's hardware to Home Assistant via MQTT
  auto-discovery: light/proximity/presence sensors, hardware buttons, relays, screen brightness and
  sleep, plus an ESPHome-compatible **Bluetooth proxy** and an acoustic **doorbell detector**.

The dashboard part is optional: the service also works next to the Home Assistant Companion app
(or Fully Kiosk, WallPanel, …) and keeps it in front with its watchdog.

PanelElevate started as [ShellyElevate](https://github.com/RapierXbox/ShellyElevate), a service
that kept the Shelly Wall Display's hardware usable next to the HA Companion app, whose WebView
was the only fast option at the time.

## Screenshots

The native dashboard renders the Home Assistant dashboards you already have (no extra
configuration), here on a Shelly Wall Display XL (1280×800) and a Sonoff NSPanel Pro (480×480).

![Shelly Wall Display XL: home view](docs/screenshots/shelly-xl-home.png)

| | | |
|:---:|:---:|:---:|
| ![Home](docs/screenshots/nspanel-home.png)<br>Home with badges and auto-entities | ![Devices](docs/screenshots/nspanel-devices.png)<br>Covers and media player | ![Climate](docs/screenshots/nspanel-climate.png)<br>Mushroom climate cards |
| ![Weather](docs/screenshots/nspanel-weather.png)<br>Weather forecast | ![Energy](docs/screenshots/nspanel-energy.png)<br>Energy per device | ![Vacuum](docs/screenshots/nspanel-vacuum.png)<br>Vacuum map with rooms |
| ![Doorbell](docs/screenshots/nspanel-doorbell-popup.png)<br>Doorbell popup (browser_mod) | ![Guest lock](docs/screenshots/nspanel-guest-lock.png)<br>Guest lock | ![Doorbell XL](docs/screenshots/shelly-xl-doorbell-popup.png)<br>Accept/decline popup |

<sub>Window view and floor plan are blurred for privacy.</sub>

## Features

| Feature | Description |
|---------|-------------|
| **Native dashboard** | Lovelace renderer without WebView: sections/masonry views, badges, core and common HACS cards, swipe navigation, see [Native dashboard](#native-dashboard) and [`dashboard/README.md`](dashboard/README.md) |
| **browser_mod emulation** | Existing popup, notification, navigate and TTS automations keep working (the panel registers as a browser_mod browser) |
| **Camera live view** | WebRTC playback of HA cameras (e.g. a doorbell), with a still until the first frame |
| **Guest lock** | While an entity is on, touches show a lock screen instead of operating the dashboard |
| **MQTT integration** | Home Assistant auto-discovery: sensors, buttons, relays, switches, events |
| **Hardware buttons** | Single/double/long press events (Shelly XL), instant on-screen feedback |
| **Screen control** | Idle dimming or real display off, wake on touch/proximity/button, auto brightness |
| **Presence** | Wide-range presence sensor (XL) or proximity (NSPanel Pro, adjustable threshold) |
| **Relays** | Built-in relays via MQTT or HTTP (Shelly: 1, NSPanel Pro: 2) |
| **Bluetooth proxy** | ESPHome-compatible proxy (passive scanning and active GATT connections), see [ESPHome Bluetooth proxy](#esphome-bluetooth-proxy) |
| **Doorbell detection** | Recognises a doorbell chime on the microphone and reports it as an event, see [Acoustic doorbell detection](#acoustic-doorbell-detection) |
| **App watchdog** | Keeps the dashboard (native or another app) in front, restart via MQTT |
| **HTTP API** | Configuration and control, bearer token |

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

### Quick setup (script)

Enable adb on the panel (Shelly: tap **F, H, F, F, H, F, H, H** on *Settings → General → About
Device*; NSPanel Pro: adb over network is on by default on port 5555), download the APK from the
[releases](https://github.com/simllll/PanelElevate/releases) and run:

```bash
scripts/setup.sh -s <panel-ip>:5555 --apk PanelElevate-v1.0.0.apk \
  --mqtt-broker tcp://<ha-ip> --mqtt-user <user> --mqtt-pass <password> --device-id kitchen \
  --ha-url http://<ha-ip>:8123 --ha-token <long-lived token of the panel user> \
  --dashboard <dashboard url_path> --browser-id "walldisplay Kitchen" \
  --ble-proxy            # optional: ESPHome Bluetooth proxy
```

The script detects the device (Shelly or NSPanel Pro), installs the APK, grants all permissions
(settings, overlay, battery optimisation, location for Bluetooth, microphone with `--doorbell`),
starts the service, sets a generated HTTP API token (printed at the end), applies the MQTT and
watchdog settings and configures the native dashboard. On the NSPanel Pro it also disables
*NSPanel Pro Tools* and stops Android from dimming on its own. Re-running it is safe;
`scripts/setup.sh --help` lists all options.

Use a long-lived token of a **non-admin** user per panel (the MQTT user can be the same user).

### Migrating from ShellyElevate

PanelElevate has a new Android package (`io.github.simllll.panelelevate`), so it installs next to
ShellyElevate (`com.stretter.shellyelevateservice`). Run the setup script with `--migrate` and the
same `--device-id` as before (the old `mqttDeviceId`): Home Assistant keeps all entities, because
MQTT topics, setting keys and discovery ids are unchanged.

- Where `adb root` works (NSPanel Pro, userdebug firmware) the old preference files are copied
  as they are, including the dashboard token and API token.
- Elsewhere the non-secret settings are read from the old app's HTTP API; pass the MQTT password
  and a dashboard token again.
- The old app is disabled, not uninstalled (`adb shell pm enable com.stretter.shellyelevateservice`
  brings it back); remove it with `adb uninstall com.stretter.shellyelevateservice` once you are happy.
- The ESPHome Bluetooth proxy is now called `panelelevate-<device id>`; Home Assistant picks up the
  new name of an existing entry by itself.

### Manual installation

1. Enable adb (see above) and install the APK: `adb install PanelElevate-v1.0.0.apk`
2. Grant the permissions:

   ```bash
   adb shell appops set io.github.simllll.panelelevate WRITE_SETTINGS allow
   adb shell appops set io.github.simllll.panelelevate SYSTEM_ALERT_WINDOW allow
   adb shell dumpsys deviceidle whitelist +io.github.simllll.panelelevate
   # Bluetooth proxy (Android 10+ also ACCESS_BACKGROUND_LOCATION), doorbell detector
   adb shell pm grant io.github.simllll.panelelevate android.permission.ACCESS_FINE_LOCATION
   adb shell pm grant io.github.simllll.panelelevate android.permission.RECORD_AUDIO
   ```

3. Start the service. The service is not exported, so `am start-foreground-service` from the adb
   shell is refused; instead launch the dashboard once (a freshly installed app gets no broadcasts
   until it was launched) and reinstall, which starts the service via `MY_PACKAGE_REPLACED` (it
   also starts on every boot):

   ```bash
   adb shell am start -n io.github.simllll.panelelevate/.dashboard.DashboardActivity
   adb install -r PanelElevate-v1.0.0.apk
   adb shell dumpsys activity services io.github.simllll.panelelevate   # verify
   ```

4. Set an HTTP API token. Settings that redirect the device (`mqttBroker`, `mqttPort`,
   `mqttUsername`, `mqttDeviceId`, `watchdogPackage`, `deviceModel`) can **only** be changed over
   HTTP with a token, and the token can only be set via adb (the receiver is protected by
   `android.permission.DUMP`):

   ```bash
   adb shell am broadcast -n io.github.simllll.panelelevate/.ApiTokenReceiver \
     -a io.github.simllll.panelelevate.SET_API_TOKEN --es token 'choose-a-long-random-token'
   curl -H "Authorization: Bearer choose-a-long-random-token" http://<panel-ip>:8080/settings
   ```

5. Configure MQTT and the watchdog over HTTP (see [Configuration](#configuration)) and the native
   dashboard via adb (see [`dashboard/README.md`](dashboard/README.md)).

## Sonoff NSPanel Pro

PanelElevate replaces the closed-source *NSPanel Pro Tools* app (`com.seaky.nspanelpro.tools`):
one app provides MQTT sensors, relays, sleep/wake, brightness, reboot, watchdog and (optionally) the
native dashboard.

The NSPanel Pro runs an Android 8.1 userdebug build (480×480 screen). Its backlight node
(`/sys/class/backlight/backlight/brightness`) is root-only and the shipped `/system/xbin/su` is
not setuid, so PanelElevate works **without root**, with the same permissions NSPanel Pro Tools
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
  it; Android's `maxRange` of 9 is meaningless). The resting level drifts with ambient light
  (~20 at night, 30–40 in daylight), so the panel learns it continuously (slowly upwards, quickly
  downwards, frozen while something is near). *Near* is a rise of at least `proximityThreshold`
  above that level (default 20, 5–500; also the *Proximity Threshold* number entity in HA) and
  *far* a drop back below half of it (hysteresis); this drives `presence` and `wakeOnProximity`.
  It only reacts to someone directly in front of the panel, not across a room. Its value is published at most every 5 s plus on every near/far change (the
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

`scripts/setup.sh` handles the NSPanel Pro as well (see [Installation](#installation)): it disables
*NSPanel Pro Tools* (it also drives the backlight and would fight over it), grants the
permissions, sets `screen_off_timeout`/`screen_brightness_mode` so that Android does not dim or
switch off on its own (PanelElevate's idle timer decides; the sleep trick restores the timeout
every time), and starts the service. Manually:

```bash
adb connect <nspanel-ip>:5555
adb shell pm disable-user --user 0 com.seaky.nspanelpro.tools
adb shell settings put system screen_off_timeout 2147483647
adb shell settings put system screen_brightness_mode 0
# then the manual installation steps above
```

`adb logcat -s PanelElevateService ScreenOffTimeout InputEventReader DeviceHelper` shows sleep/wake
(`Switching screen off via screen-off timeout`, `Screen-off timeout restored to …`) and which input
devices are read.

### Migrating from NSPanel Pro Tools

NSPanel Pro Tools announced its own MQTT device. After switching, delete that device in Home
Assistant (Settings → Devices → MQTT) and update automations to the new entities
(`<id>` = `mqttDeviceId`, the entity ids follow the device name *Sonoff NSPanel Pro*):

| NSPanel Pro Tools | PanelElevate |
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

Instead of the HA Companion app, PanelElevate can show your dashboard itself: a native
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

## ESPHome Bluetooth proxy

Both panels have Bluetooth LE. With `bleProxyEnabled` (default `false`) PanelElevate speaks
the ESPHome native API on TCP port **6053** and Home Assistant's ESPHome integration adds the
panel as a Bluetooth proxy, like an ESP32 running `bluetooth_proxy:`. It forwards
advertisements (plant sensors, Oral-B, iBeacons, …) and lets integrations open **active GATT
connections** through it (e.g. La Marzocco, Xiaomi plant sensor battery/history).

- Plaintext API without password (no API encryption key). Device name
  `panelelevate-<mqttDeviceId>`, announced via mDNS (`_esphomelib._tcp`), so HA offers it under
  *Discovered*; adding it manually by IP and port 6053 works too.
- Feature flags: passive scan, active connections (3 slots), remote caching, pairing, cache
  clearing, raw advertisements, scanner state. Android always scans actively (scan responses
  are requested); `BluetoothScannerSetMode` is answered with the current (active) mode.
- GATT handles are the real ATT handles, so HA's service cache stays valid across connections.
- No entities: the proxy only shows up as a Bluetooth scanner/proxy in HA.
- Wi-Fi impact measured with continuous low-latency scanning: ping to the HA host rose from
  2.6 to 2.9 ms on average on the Wall Display XL (9.2 → 10.3 ms on the NSPanel Pro), no extra
  packet loss.

Setup (no root needed):

```sh
# Location permission (BLE scans return nothing without it) and location services on
adb shell pm grant io.github.simllll.panelelevate android.permission.ACCESS_FINE_LOCATION
adb shell pm grant io.github.simllll.panelelevate android.permission.ACCESS_COARSE_LOCATION
adb shell settings put secure location_mode 3
# Bluetooth must be on
adb shell settings get global bluetooth_on   # 1
# Enable the proxy (or the "Bluetooth Proxy" switch in HA via MQTT, or bleProxyEnabled via the HTTP API)
adb shell am broadcast -n io.github.simllll.panelelevate/.AdbSettingReceiver \
    -a io.github.simllll.panelelevate.SET_SETTING --es key bleProxyEnabled --es value true
```

Then add the ESPHome device in HA (Settings → Devices & services, discovered
`panelelevate-…`, or *Add integration → ESPHome* with the panel's IP, port 6053, no
encryption key). Notes:

- **NSPanel Pro (Android 8.1)**: Android 8.1 pauses *unfiltered* BLE scans while the display
  is off; the proxy passes an empty scan filter and keeps scanning with the display off
  (verified: ~400 results in 30 s while asleep). The location permission must be granted as a
  runtime permission (`adb shell pm grant io.github.simllll.panelelevate
  android.permission.ACCESS_FINE_LOCATION`), otherwise Android rejects the scan with a
  SecurityException in logcat. The Bluetooth MAC is not readable for apps there, so the
  proxy reports only the Wi-Fi MAC (HA then uses it as the scanner's source address).
- **Wall Display XL (Android 11)**: scans keep running with the display dimmed.

## Acoustic doorbell detection

For door bells that ring an indoor unit only locally (no event in HA), the panel can listen
for the ringtone with its microphone (`doorbellDetectEnabled`, default `false`, also the
"Doorbell Detection" switch in HA). On a match it fires the MQTT event entity **Doorbell sound**
(`event_types: [ring]`, device class `doorbell`); combine it in HA with your intercom's own
ring events (e.g. "tone heard and no intercom call within ±10 s = flat door").

- Continuous AudioRecord (source MIC, 16 kHz mono) in the service, also with the display off;
  the microphone is released when the setting is off.
- Goertzel filters on the three notes of the BTicino chime (1760 Hz → 2090 Hz → 1395 Hz, ±3 %)
  and reference frequencies between them in 64 ms blocks. A note counts when it is above
  `doorbellMinLevel` (default 100, ≈ a sine of amplitude 200) and `doorbellMinRatio` (default 8)
  times the strongest reference bin; the notes must follow in order, each ≥ 2 blocks, ≤ 0.6 s
  apart. Cooldown 5 s. Steps, doors and voices (below 1 kHz, not tonal) do not match.
- Log tag `DoorbellDetector` (levels and ratios of each hit).
- Needs `adb shell pm grant io.github.simllll.panelelevate android.permission.RECORD_AUDIO`.

Recording samples for tuning (adb only; the detector pauses while a sample is recorded):

```sh
adb shell am broadcast -n io.github.simllll.panelelevate/.audio.AudioSampleReceiver \
    -a io.github.simllll.panelelevate.RECORD_AUDIO_SAMPLE --ei seconds 60 [--es source mic]
adb pull /sdcard/Android/data/io.github.simllll.panelelevate/files/audio/
```

## Recommended Setup

1. **PanelElevate with the native dashboard** (`watchdogPackage` `dashboard`): fastest on these
   panels, popups and TTS from browser_mod automations keep working.
2. On the Shelly, an alternative launcher so that Shelly's own app does not start, e.g.
   [Ultra Small Launcher](https://blakadder.com/assets/files/ultra-small-launcher.apk)
   (`adb install ultra-small-launcher.apk`, set as default; `adb shell input keyevent 3` opens it).
3. If you prefer the web dashboard: the
   [Home Assistant Companion app (minimal)](https://github.com/home-assistant/android/releases)
   with `watchdogPackage` `io.homeassistant.companion.android.minimal`.

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

Set `watchdogPackage` to `dashboard` (or the own package name `io.github.simllll.panelelevate`)
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
adb shell am broadcast -n io.github.simllll.panelelevate/.AdbSettingReceiver \
  -a io.github.simllll.panelelevate.SET_SETTING --es key watchdogPackage --es value dashboard

# back to the HA Companion app
adb shell am broadcast -n io.github.simllll.panelelevate/.AdbSettingReceiver \
  -a io.github.simllll.panelelevate.SET_SETTING --es key watchdogPackage \
  --es value io.homeassistant.companion.android.minimal

# enable/disable, interval in seconds
adb shell am broadcast -n io.github.simllll.panelelevate/.AdbSettingReceiver \
  -a io.github.simllll.panelelevate.SET_SETTING --es key watchdogEnabled --es value true
adb shell am broadcast -n io.github.simllll.panelelevate/.AdbSettingReceiver \
  -a io.github.simllll.panelelevate.SET_SETTING --es key watchdogInterval --es value 10
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

All topics are prefixed with `shellyelevateservice/<client-id>/` (kept from ShellyElevate so existing
Home Assistant entities and automations keep working after the rename to PanelElevate).

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

PanelElevate publishes MQTT discovery config to `homeassistant/device/<client-id>/config`. After connecting to MQTT, the device will automatically appear in Home Assistant with:

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
| `bleProxyEnabled` | boolean | `false` | ESPHome Bluetooth proxy on port 6053 (see [ESPHome Bluetooth proxy](#esphome-bluetooth-proxy); also settable via adb and the MQTT switch) |
| `doorbellDetectEnabled` | boolean | `false` | Acoustic doorbell detection via the microphone (see [Acoustic doorbell detection](#acoustic-doorbell-detection); also settable via adb and the MQTT switch) |
| `doorbellMinRatio` | int | `8` | Doorbell detector: minimum ratio of a note to the strongest reference frequency |
| `doorbellMinLevel` | int | `100` | Doorbell detector: minimum note level (Goertzel magnitude) |
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
