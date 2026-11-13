#!/usr/bin/env bash
# PanelElevate setup over adb: install, permissions, service start, settings, dashboard.
# Works for the Shelly Wall Displays and the Sonoff NSPanel Pro (detected from ro.product.model).
#
#   scripts/setup.sh -s <adb-serial> --apk PanelElevate-1.0.0.apk \
#       --mqtt-broker tcp://192.168.1.20 --mqtt-user panel --mqtt-pass secret --device-id kitchen \
#       --ha-url http://192.168.1.20:8123 --ha-token <long-lived token> --dashboard my-dashboard
#
# Run with --help for all options. Re-running is safe: every step is idempotent.
set -euo pipefail

NEW=io.github.simllll.panelelevate
OLD=com.stretter.shellyelevateservice
NSPANEL_TOOLS=com.seaky.nspanelpro.tools

SERIAL="" APK="" API_TOKEN="" MQTT_BROKER="" MQTT_PORT=1883 MQTT_USER="" MQTT_PASS="" DEVICE_ID=""
HA_URL="" HA_TOKEN="" DASHBOARD="" BROWSER_ID="" WATCHDOG="dashboard" BLE=false DOORBELL=false
MIGRATE=false DISABLE_OLD=true

usage() {
  cat <<'EOF'
Usage: scripts/setup.sh -s <adb-serial> --apk <PanelElevate.apk> [options]

Connection
  -s, --serial SERIAL      adb device (e.g. 192.168.1.50:5555)
  --apk FILE               PanelElevate APK to install

Service (HTTP API / MQTT)
  --api-token TOKEN        HTTP API bearer token (generated and printed if omitted)
  --mqtt-broker URL        e.g. tcp://192.168.1.20 (MQTT stays off without it)
  --mqtt-port PORT         default 1883
  --mqtt-user USER, --mqtt-pass PASS
  --device-id ID           MQTT device id / client id (keep the old one to keep HA entities)
  --watchdog TARGET        dashboard (default), a package name, or "off"
  --ble-proxy              enable the ESPHome Bluetooth proxy (port 6053)
  --doorbell               enable the microphone doorbell chime detector

Native dashboard
  --ha-url URL             Home Assistant URL as seen from the panel
  --ha-token TOKEN         long-lived token of the (non-admin) panel user
  --dashboard URL_PATH     dashboard url_path (default dashboard if omitted)
  --browser-id ID          browser_mod browser id (e.g. "walldisplay Kitchen")

Migration from ShellyElevate (com.stretter.shellyelevateservice)
  --migrate                copy the old app's settings: whole preference files where adb root
                           works (NSPanel userdebug firmware, keeps all tokens), otherwise the
                           non-secret settings from its HTTP API (pass --mqtt-pass/--ha-token)
  --keep-old               do not disable the old app afterwards
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    -s|--serial) SERIAL="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --api-token) API_TOKEN="$2"; shift 2 ;;
    --mqtt-broker) MQTT_BROKER="$2"; shift 2 ;;
    --mqtt-port) MQTT_PORT="$2"; shift 2 ;;
    --mqtt-user) MQTT_USER="$2"; shift 2 ;;
    --mqtt-pass) MQTT_PASS="$2"; shift 2 ;;
    --device-id) DEVICE_ID="$2"; shift 2 ;;
    --watchdog) WATCHDOG="$2"; shift 2 ;;
    --ble-proxy) BLE=true; shift ;;
    --doorbell) DOORBELL=true; shift ;;
    --ha-url) HA_URL="$2"; shift 2 ;;
    --ha-token) HA_TOKEN="$2"; shift 2 ;;
    --dashboard) DASHBOARD="$2"; shift 2 ;;
    --browser-id) BROWSER_ID="$2"; shift 2 ;;
    --migrate) MIGRATE=true; shift ;;
    --keep-old) DISABLE_OLD=false; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[ -n "$SERIAL" ] && [ -n "$APK" ] || { usage >&2; exit 2; }
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 2; }

adb_() { adb -s "$SERIAL" "$@"; }
sh_() { adb -s "$SERIAL" shell "$@" | tr -d '\r'; }
log() { printf '\033[1m==> %s\033[0m\n' "$*"; }
installed() { sh_ pm list packages "$1" | grep -qx "package:$1"; }

adb connect "$SERIAL" >/dev/null 2>&1 || true
MODEL=$(sh_ getprop ro.product.model)
SDK=$(sh_ getprop ro.build.version.sdk)
case "$MODEL" in
  px30_evb) DEVICE=nspanel ;;
  *) DEVICE=shelly ;;
esac
log "Device: $MODEL (Android API $SDK, profile $DEVICE)"

# --- Migration: read the old app's settings while it still runs ------------------------------
OLD_PREFS_DIR=""
if $MIGRATE && installed "$OLD"; then
  if [ "$(sh_ getprop ro.debuggable)" = "1" ] && adb_ root >/dev/null 2>&1; then
    sleep 3; adb connect "$SERIAL" >/dev/null 2>&1 || true; adb_ wait-for-device
    OLD_PREFS_DIR=$(mktemp -d); chmod 700 "$OLD_PREFS_DIR"
    for f in ShellyElevateService.xml dashboard.xml screen_power.xml; do
      sh_ "cat /data/data/$OLD/shared_prefs/$f 2>/dev/null" > "$OLD_PREFS_DIR/$f" || true
    done
    log "Migration: copied the old preference files (adb root)"
  else
    LOCAL_PORT=$(adb_ forward tcp:0 tcp:8080 | tr -d '\r')
    OLD_SETTINGS=$(curl -s -m 5 "http://127.0.0.1:$LOCAL_PORT/settings" || true)
    adb_ forward --remove tcp:$LOCAL_PORT >/dev/null || true
    if echo "$OLD_SETTINGS" | grep -q '"success":true'; then
      get() { echo "$OLD_SETTINGS" | python3 -c "import sys,json; v=json.load(sys.stdin)['settings'].get('$1'); print('' if v is None else v)"; }
      [ -z "$MQTT_BROKER" ] && MQTT_BROKER=$(get mqttBroker); [ -z "$MQTT_USER" ] && MQTT_USER=$(get mqttUsername)
      [ -z "$DEVICE_ID" ] && DEVICE_ID=$(get mqttDeviceId); MQTT_PORT=$(get mqttPort)
      [ "$(get bleProxyEnabled)" = "True" ] && BLE=true; [ "$(get doorbellDetectEnabled)" = "True" ] && DOORBELL=true
      log "Migration: read the old settings over HTTP (device id $DEVICE_ID); secrets must be passed again"
    else
      echo "Migration: the old app's HTTP API did not answer (API token set?); continuing without" >&2
    fi
  fi
fi

# --- Install -----------------------------------------------------------------------------------
log "Installing $APK"
adb_ install -r "$APK" | tail -1
[ "$DEVICE" = nspanel ] && installed "$NSPANEL_TOOLS" && { sh_ pm disable-user --user 0 "$NSPANEL_TOOLS" >/dev/null; log "Disabled NSPanel Pro Tools"; }
if $DISABLE_OLD && installed "$OLD"; then sh_ pm disable-user --user 0 "$OLD" >/dev/null; log "Disabled the old app ($OLD); re-enable with: adb shell pm enable $OLD"; fi

# --- Permissions ---------------------------------------------------------------------------------
log "Granting permissions"
sh_ appops set $NEW WRITE_SETTINGS allow
sh_ appops set $NEW SYSTEM_ALERT_WINDOW allow
sh_ dumpsys deviceidle whitelist +$NEW >/dev/null
perms="ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION"
[ "$SDK" -ge 29 ] && perms="$perms ACCESS_BACKGROUND_LOCATION"
$DOORBELL && perms="$perms RECORD_AUDIO"
for p in $perms; do sh_ pm grant $NEW android.permission.$p 2>/dev/null || true; done
if [ "$DEVICE" = nspanel ]; then
  # ShellyElevate's idle timer decides; Android must not dim or switch off on its own
  sh_ settings put system screen_off_timeout 2147483647
  sh_ settings put system screen_brightness_mode 0
fi

# --- Migrated preference files (root) --------------------------------------------------------------
if [ -n "$OLD_PREFS_DIR" ] && [ -s "$OLD_PREFS_DIR/ShellyElevateService.xml" ]; then
  APP_UID=$(sh_ stat -c %u /data/data/$NEW)
  sh_ "mkdir -p /data/data/$NEW/shared_prefs"
  adb_ shell "cat > /data/data/$NEW/shared_prefs/PanelElevateService.xml" < "$OLD_PREFS_DIR/ShellyElevateService.xml"
  for f in dashboard.xml screen_power.xml; do
    [ -s "$OLD_PREFS_DIR/$f" ] && adb_ shell "cat > /data/data/$NEW/shared_prefs/$f" < "$OLD_PREFS_DIR/$f"
  done
  sh_ "chown -R $APP_UID:$APP_UID /data/data/$NEW/shared_prefs; chmod 660 /data/data/$NEW/shared_prefs/*; restorecon -R /data/data/$NEW/shared_prefs"
  rm -rf "$OLD_PREFS_DIR"
  log "Migration: preference files installed"
fi

# --- Start the service ------------------------------------------------------------------------
running() { sh_ "ps -A -o NAME" | grep -qx "$NEW"; }
if ! running; then
  log "Starting the service"
  if [ "$(sh_ id -u)" = "0" ]; then
    sh_ am start-foreground-service -n $NEW/.PanelElevateService >/dev/null
  else
    # A never launched app is "stopped" and gets no broadcasts: launch once, then a reinstall
    # delivers MY_PACKAGE_REPLACED, which starts the (not exported) service.
    sh_ am start -n $NEW/.dashboard.DashboardActivity >/dev/null
    adb_ install -r "$APK" | tail -1
  fi
  for _ in $(seq 1 30); do running && break; sleep 2; done
fi
running || { echo "The service did not start; check: adb logcat -s PanelElevateService" >&2; exit 1; }
[ "$(sh_ id -u)" = "0" ] && { adb_ unroot >/dev/null 2>&1 || true; sleep 3; adb connect "$SERIAL" >/dev/null 2>&1 || true; adb_ wait-for-device; }

# --- HTTP API token and settings ----------------------------------------------------------------
if [ -z "$API_TOKEN" ]; then
  API_TOKEN=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)
  GENERATED_TOKEN=true
fi
sh_ am broadcast -n $NEW/.ApiTokenReceiver -a $NEW.SET_API_TOKEN --es token "'$API_TOKEN'" >/dev/null

json=$(python3 - "$MQTT_BROKER" "$MQTT_PORT" "$MQTT_USER" "$MQTT_PASS" "$DEVICE_ID" "$WATCHDOG" "$BLE" "$DOORBELL" <<'EOF'
import json, sys
broker, port, user, pw, dev, watchdog, ble, doorbell = sys.argv[1:]
s = {"bleProxyEnabled": ble == "true", "doorbellDetectEnabled": doorbell == "true"}
if broker:
    s.update(mqttEnabled=True, mqttBroker=broker, mqttPort=int(port or 1883))
    if user: s["mqttUsername"] = user
    if pw: s["mqttPassword"] = pw
if dev: s["mqttDeviceId"] = dev
if watchdog == "off": s["watchdogEnabled"] = False
else: s.update(watchdogEnabled=True, watchdogPackage=watchdog)
print(json.dumps(s))
EOF
)
LOCAL_PORT=$(adb_ forward tcp:0 tcp:8080 | tr -d '\r')
for _ in $(seq 1 15); do curl -s -m 2 -o /dev/null "http://127.0.0.1:$LOCAL_PORT/" && break; sleep 2; done
result=$(curl -s -m 10 -X POST -H "Authorization: Bearer $API_TOKEN" -H 'Content-Type: application/json' \
  --data-binary "$json" "http://127.0.0.1:$LOCAL_PORT/settings")
adb_ forward --remove tcp:$LOCAL_PORT >/dev/null || true
echo "$result" | grep -q '"success":true' && log "Settings applied" || { echo "Settings failed: $result" >&2; exit 1; }

# --- Native dashboard ---------------------------------------------------------------------------
if [ -n "$HA_URL" ] || [ -n "$HA_TOKEN" ] || [ -n "$DASHBOARD" ] || [ -n "$BROWSER_ID" ]; then
  args=()
  [ -n "$HA_URL" ] && args+=(--es url "'$HA_URL'")
  [ -n "$HA_TOKEN" ] && args+=(--es token "'$HA_TOKEN'")
  [ -n "$DASHBOARD" ] && args+=(--es dashboard "'$DASHBOARD'")
  [ -n "$BROWSER_ID" ] && args+=(--es browser_id "'$BROWSER_ID'")
  sh_ am broadcast -n $NEW/.dashboard.DashboardConfigReceiver -a $NEW.dashboard.CONFIGURE "${args[@]}" | grep -o 'data=.*' || true
fi

log "Done."
[ "${GENERATED_TOKEN:-false}" = true ] && echo "HTTP API token (store it): $API_TOKEN"
$BLE && echo "Bluetooth proxy: add it in Home Assistant (ESPHome, port 6053, no encryption key)."
exit 0
