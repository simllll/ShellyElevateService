# Native Lovelace dashboard (`:dashboard`)

A native (Kotlin + Jetpack Compose) renderer for a Home Assistant dashboard, built for the Shelly
Wall Display XL (1280×800) and the Sonoff NSPanel Pro (480×480), whose WebViews are too slow for
the HA frontend (popups took 1–4 s to fill on the XL). It loads the dashboard config live
from HA (`lovelace/config`) and renders every card type natively, looking like the web frontend
(default theme, dark mode, frontend `20261028.5`). Unknown card types render a placeholder that
shows the type name.

## Configure and launch

The connection is stored in the SharedPreferences `dashboard` of the `:dashboard` process and set
via adb (the receiver is protected by `android.permission.DUMP`, which only the shell holds):

```sh
adb shell am broadcast -n io.github.simllll.panelelevate/.dashboard.DashboardConfigReceiver \
    -a io.github.simllll.panelelevate.dashboard.CONFIGURE \
    --es url http://192.168.1.20:8123 \
    --es token 'eyJ…long-lived-token…' \
    --es dashboard wohnbereich-walldisplay

adb shell am start -n io.github.simllll.panelelevate/.dashboard.DashboardActivity
```

All extras are optional; an empty value clears a setting. `--es scale 0.75` zooms the UI
(1 CSS px = `scale` dp; default 1, which reproduces the web layout on a 1280×800 mdpi screen).
`--es browser_id 'walldisplay Eingang'` sets the browser_mod id (see below).
`--es return_home_after_s 60` sets how long (seconds without a touch) the dashboard stays on
another view before returning to the first one (default 120, `0` = never).
Guest lock (see below): `--es lock_entity binary_sensor.lock_dashboards` (default; `none` = off),
`--es lock_video /local/screensaver.mp4` (default; `none` = no video),
`--es lock_sleep_after_s 10` (default).
A running dashboard applies changes immediately. The activity runs in its own process
(`:dashboard`), so a crash never takes the background service down; after a crash it restarts
itself (at most 3 times in 5 minutes).

To keep the dashboard in front with the service's app watchdog (instead of the HA app), set
`watchdogPackage` to `dashboard`; see "Native dashboard as watchdog target" in the main README:

```sh
adb shell am broadcast -n io.github.simllll.panelelevate/.AdbSettingReceiver \
    -a io.github.simllll.panelelevate.SET_SETTING --es key watchdogPackage --es value dashboard
```

Use a long-lived token of a **non-admin** HA user per panel (e.g. the panel's own user): the
dashboard then sees exactly what that user sees, and automations that map users to browsers
(`context.user_id`) keep working.

## Supported cards

| Kind | Types |
|---|---|
| Layout | `sections` and `masonry` views (`panel`/`sidebar` views show a "not supported" message), view badges (entity, `custom:mushroom-template-badge`), `grid`, `horizontal-stack`, `vertical-stack` |
| Core cards | `alarm-panel`, `button`, `energy-date-selection`, `energy-devices-detail-graph`, `entities`, `entity`, `heading`, `history-graph`, `iframe` (video URLs only), `markdown`, `picture`, `picture-entity`, `picture-glance`, `tile`, `weather-forecast` |
| Entity rows | default entity rows (sensor, toggle, climate, cover, fan, humidifier, lock, valve, scene, script, input_*), `attribute`, `button`/`call-service`/`perform-action`, `divider`, `section`, `simple-entity` |
| HACS cards | `custom:auto-entities`, `custom:bubble-card` (pop-up, button, media-player), `custom:button-card`, `custom:fab-card`, `custom:mushroom-climate-card`, `custom:mushroom-template-card`, `custom:refreshable-picture-card`, `custom:template-entity-row`, `custom:timer-bar-card`, `custom:xiaomi-vacuum-map-card` |

Everything else renders a placeholder with the card type.

## Limitations

- Only the configured dashboard is shown: no sidebar, settings, other dashboards or editing.
- Card CSS (`styles`, `card_mod`, bubble-card `styles`) is not applied.
- SVG pictures are drawn as a static first frame (no animations, no filters).
- The more-info dialog is minimal (state and basic controls, no history, attributes or settings).
- Energy cards: day periods only (no week/month/year, no compare).
- `mushroom-climate-card`: no `hvac_modes` buttons, no `collapsible_controls`.
- `button-card` JavaScript templates run through a small evaluator (no JS engine); a template
  outside its subset renders as empty (logged as `unsupported template`).
- browser_mod `javascript` is not executed (only the heartbeat `hass.callService(…)`).
- New HA frontend features do not arrive automatically; each card is a port.
- Camera live view: WebRTC only (no HLS or MJPEG streams, no two-way audio/talk button, no
  `camera_view: auto` streaming); cameras without `web_rtc` show refreshing stills. BTicino
  intercoms end a live view after ~30 s (`terminate`), the card then reconnects within its retry
  budget. Opening a BTicino live view while that door unit is ringing *answers* the call (the
  integration's answer mode, same as the web frontend).
- `picture-entity`/`picture-glance`: no `state_filter`/CSS filters, no `dark_mode_image`; media
  source images (`media-source://`) are not resolved.

## Architecture

```
DashboardActivity ─ DashboardScreen ─ SectionsView ─ GridSection ─ CardRenderer.Content
                                    └ MasonryView ─ ViewBadges, columns ─ CardRenderer.Content
        │                 │ PopupHost.Layer (more-info, browser_mod, bubble pop-ups, toasts)
        └ DashboardRuntime (process singleton)
             ├ HassClient        WebSocket API, reconnect, per-entity StateFlows
             ├ CardRegistry      type → CardFactory   (CardCatalog registers everything)
             ├ RowRegistry       entities-card row types
             ├ ActionHandler     tap/hold/double-tap actions, browser_mod emulation
             ├ PopupHost/Navigator  popup layer, location hash, view switching
             ├ StateFormatter    translated states, numbers, relative times
             ├ IconResolver      default entity icons (backend icon translations)
             └ ImageLoader       OkHttp + subsampling + LRU
```

| Package | Contents |
|---|---|
| `hass` | `HassClient`, `EntityStore` (compressed `subscribe_entities` diffs), `EntitySubscriptionPlanner` (ref-counted entity filters), `EntityLogic` (`isActive`, toggle services) |
| `lovelace` | `SectionsView`, `GridSection`, `MasonryView`, `CardGrid`/`SectionsGrid`/`StretchRow` layouts, `GridMath` (CSS grid placement + track sizing), `MasonryMath` (column count, card sizes, column assignment) |
| `badges` | View badges: `BadgeSpec` (config parsing/migration), `ViewBadges` (entity badge, `custom:mushroom-template-badge`) |
| `cards` | `CardRenderer` API, `CardContext`, `CardRegistry`, `CardCatalog`, `HaCard`, `StateBadge`, core cards (incl. `entity`, and `energy-date-selection` / `energy-devices-detail-graph` simplified to day periods: stacked hourly device bars + untracked consumption) |
| `rows` | `RowRenderer` API, `RowRegistry`, `GenericEntityRow` and the core rows |
| `actions` | `ActionHandler`, `Modifier.haActions`, `Navigator` |
| `popup` | `PopupHost`, `HashPopup`, `HaDialog`, browser_mod popup, more-info dialog, toasts |
| `browsermod` | browser_mod protocol, `BrowserMod` client, `hass.callService` heartbeat parser |
| `html` | HTML subset parser and renderer, CSS value helpers |
| `theme` | `HaTheme` tokens (colors, typography, dimensions) and `CssColors` (CSS colors, state colors) |
| `icons` | MDI webfont (`assets/mdi.ttf`) + name map (`assets/mdi-map.txt`), `MdiIcon` |
| `markdown` | Markdown subset parser and renderer (`ha-markdown` look) |
| `format`, `image`, `ui`, `json` | formatting, image loading (bitmaps and SVG via AndroidSVG), controls (`HaSwitch`, `HaButton`, `LoopingVideo`, …), lenient JSON accessors |
| `camera` | Camera live view: `WebRtcSignaling` (HA camera WebRTC messages, candidate queues), `SdpOffer` (offer reduction), `LiveStreamPolicy` (retry/throttle), `LiveStream`/`LiveStreamHub` (libwebrtc sessions, shared per camera), `CameraPicture` (still/live composable, `TextureView` renderer) |
| `custom` | HACS cards: `button-card` (+ tiny JS-template evaluator, no JS engine), `auto-entities` (filter/sort engine, registry on demand via `get_entries`), `mushroom-template-card`, `template-entity-row`, `timer-bar-card`, `fab-card` (floating button drawn in `PopupHost.FloatingLayer`, fixed above the view, touch target 12px larger than the circle), `mushroom-climate-card` (state, hvac_action badge, temperature control) |

### Layout fidelity

`SectionsView` follows `hui-sections-view`: columns of 320–500px (`max_columns`, default 4),
32px column gap (8px below 600px width), 24px row gap and padding, `column_span`, `row_span`,
`dense_section_placement`. `GridSection` follows `hui-grid-section`: 12 × span columns, 56px rows,
8px gaps, CSS auto-placement; cards with numeric `rows` get exactly
`rows × 64 − 8` px, auto cards their content height, stretched to the row (CSS
`align-items: stretch`, via intrinsic measurement). Sizes come from `CardRenderer.gridOptions()`
merged with the config's `grid_options` (legacy `layout_options` are migrated).

`MasonryView` (views without `type`, or `masonry`) follows `hui-masonry-view`: one column per
matching `min-width` breakpoint (300/600/900/1200px, so a 480px panel has one column), columns
`flex: 1 0 0` up to 500px (600px below 600px width), centered, 4px outer margins, cards with
`4px 4px 8px` margins (the frontend's narrow-screen rule that drops the side margins never applies
because a more specific selector wins). Cards are assigned once with `getColumnIndex` (first
column below size 5, else the smallest) using `MasonryMath.cardSize`, a config-based port of each
card's `getCardSize()`. Badges (`ViewBadges`) sit above in a centered, wrapping row with 8px gaps:
entity badges (state, name label, state color / primary when active) and
`custom:mushroom-template-badge` (label, content, icon, color, picture templates); `visibility`
and `disabled` hide a badge, a Mushroom badge with only empty templates is hidden. The sections
view does not render badges.

On screens up to 450px wide or 500px high (e.g. the 480×480 NSPanel Pro) standard dialogs,
browser_mod popups and toasts are fullscreen / full width like `ha-dialog` and `ha-toast`.
The `iframe` card has no WebView: video URLs (`.mp4`, `.webm`, …) play natively (muted, looped,
autoplay, released when off-screen or when the activity stops), other URLs show a placeholder.

### Navigation

`navigate` actions switch views (`/dashboard/view` by `path` or index, `#hash` for bubble-card
pop-ups). With the dashboard's `swipe_nav` (hass-swipe-navigation) horizontal swipes go to the
next/previous view like a pager: the current and the neighbouring view move with the finger, and
on release it switches at `swipe_amount` (% of the width, default 15) or on a fling, otherwise it
springs back. The two neighbours stay composed off-screen, so the next view is already laid out
when the drag starts. `wrap`, `animate` (`swipe` as described; `fade`/`flip`: fade in; `none`);
subviews and `visible: false` views are skipped. Floating buttons, bubble pop-ups and videos only
run in the active view. The swipe only starts when no card consumed the movement
first (sliders, the vacuum map, horizontal drags) and vertical scrolling wins when it passes its
touch slop first. The Android back key closes popups, then walks back through the view history,
then goes to the first view. After `return_home_after_s` without a touch the first view is shown.

### Performance

Only entities on screen are subscribed (`subscribe_entities` with `entity_ids`, extended
incrementally when a popup opens). Every entity has its own `StateFlow`; a card reading
`ctx.entity(id)` recomposes only when that entity changes. Templates are shared and replayed
(`renderTemplate` is cached per template + variables). No WebView, no Material dependency.

### browser_mod

The dashboard registers as a browser_mod browser (`browser_mod/connect`, `register`, `update`),
by default as `walldisplay Wohnbereich`, the id the web dashboard used, so automations that
target it keep working (`--es browser_id 'walldisplay Eingang'`, `none` disables it; do not
run the web dashboard with the same id at the same time). It reports the `path`,
`visibility` (activity started/stopped), size, user and activity sensors and runs `popup`
(card config or HTML string, timeout progress bar, buttons, `dismiss_action`/`timeout_action`),
`close_popup`, `notification`, `navigate`, `refresh`, `more_info` and `sequence`.
`javascript` is not executed: a single `hass.callService(…)` with literal arguments (the
watchdog heartbeat) is run as a service call, anything else is logged and ignored.
HTML popup content is drawn natively by `html/Html.kt` (block/inline elements, `ha-icon`,
inline styles for color, font size/weight, opacity, alignment, margins and paddings).

browser_mod's `media_player.<browser>` (e.g. `media_player.walldisplay_eingang`) is emulated by
`BrowserModPlayer` with Android's `MediaPlayer`: `media_player.play_media` (with or without
`announce`), `tts.speak` (`media_player_entity_id`), `volume_set`, `volume_mute`, `media_play`,
`media_pause`, `media_stop`, `media_seek`, `turn_off`. Relative URLs such as
`/api/tts_proxy/….mp3` (signed by HA) are resolved against the dashboard URL; URLs on HA's
`internal_url`/`external_url` are moved to the dashboard URL as well, and only requests to HA
carry the token. `volume_level` is the media stream volume of the device (the one the service's
MQTT volume sets; hardware/MQTT changes are reported back), `volume_mute` mutes only this player.
`announce: true` (which `tts.speak` sends) plays on a separate player (`USAGE_ASSISTANT`,
transient audio focus) and resumes paused media afterwards. State, volume, mute, source, duration
and position are reported like browser_mod.js (`browser_mod/update {player}`; position every
10 s while playing). Playback runs in the dashboard process and continues while the activity is
stopped or the screen is off. Video URLs play their audio only.

### Pictures

`ImageLoader` decodes bitmaps subsampled to the display size and rasterizes SVGs with AndroidSVG.
SVGs with a fixed root `width`/`height` (e.g. animated weather icons) are scaled into the target
size through their viewBox (or `0 0 width height` when the viewBox is missing).

### Camera live view (WebRTC)

Camera pictures follow `hui-image`: `picture-entity` (camera entity) and `picture-glance`
(`camera_image`) show stills from the camera's `entity_picture` (`/api/camera_proxy/…`), refreshed
every 10 s while on screen. With `camera_view: live` they play the camera's WebRTC stream
natively (libwebrtc, `io.getstream:stream-webrtc-android`). `custom:refreshable-picture-card`
accepts `camera_view: live` too, with a camera `entity` or a `url` of the form
`/api/camera_proxy/camera.x` (so a doorbell popup only needs the extra key):

```yaml
type: custom:refreshable-picture-card
url: /api/camera_proxy/camera.eingang_bticino_intercom_schreygasse_wien_entrance_panel
camera_view: live
noMargin: true
```

Signaling uses HA's camera WebSocket API like `ha-web-rtc-player`: `camera/capabilities`
(only cameras whose `frontend_stream_types` contain `web_rtc`), `camera/webrtc/get_client_config`
(ICE servers, data channel), the `camera/webrtc/offer` subscription (`session`, `answer`,
`candidate`, `error` events; unsubscribing closes the session on the camera) and
`camera/webrtc/candidate` for trickled local candidates (sent once the `session_id` is known).
The offer is receive-only (audio + video transceivers) and reduced by `SdpOffer` to one H264
profile (constrained baseline, payload type 109) and Opus (111), about 2 KB: BTicino intercoms
(`bticino_intercom`) reboot on offers above ~8 KB and send exactly those payload types; offers
above 6000 bytes are never sent. Decoding uses the hardware H264 decoder on the shared EGL
context; frames are drawn on a `TextureView` (`EglRenderer`), so they clip to the card's corners.

- The still stays visible until the first frame; without `aspect_ratio` the picture takes the
  video's aspect ratio (16:9 before the first frame). `fit_mode` `cover`/`contain`/`fill` apply.
- Audio is received but muted (no playout at all); a speaker button in the corner unmutes it.
- A stream runs only while its card is composed, its view active and the activity started: closing
  the popup, swiping away or the screen going off ends the session (1.5 s grace, so a quick
  re-layout keeps it). Several cards showing the same camera share one session (`LiveStreamHub`).
- Failures are logged (`adb logcat -s CameraLive`) and retried at most 3 times (5 s, then 20 s
  apart; offers for one camera are always ≥ 5 s apart); then the card falls back to stills
  refreshed every 10 s. A stream that played for over 60 s gets a fresh retry budget when it ends.
  A dropped HA connection ends the session (the offer is not re-sent automatically).
- Only ARM ABIs are packaged (`arm64-v8a`, `armeabi-v7a`); libwebrtc adds ~9.5 MB to the APK.

### Hardware buttons

HA automations stay the source of truth for the four hardware buttons (MQTT `button_event`,
browser_mod popups). For instant feedback the service also sends a package-restricted broadcast
(`ButtonFeedbackContract.ACTION`, extras `button` 1–4 and `event` `down`/`single`/`double`/`long`) to a
not-exported receiver the dashboard registers only while the activity is started (no effect, and
no process start, when the dashboard is not running). The service sends `event` `down` on the raw
key-down, so a small pill ("Taste 2") appears at the top center immediately; when the action is
detected (single after the 300 ms double-click window, long after 500 ms) the same pill is
relabeled ("Taste 2 · 2×", "Taste 2 · lang"). It disappears 1.5 s after the button's last event,
or when the next browser_mod popup arrives.

### Guest lock

While the lock entity (`lock_entity`, default `binary_sensor.lock_dashboards`) is `on`, the
dashboard looks and updates as usual, but no touch reaches it: a transparent layer above the view
and the floating buttons swallows every gesture (tap, swipe, scroll, long press) and the back key.
A touch instead shows the lock overlay, the `lock_video` (muted, looped, letterboxed on black,
via the dashboard URL with the token) with a lock icon and "Gesperrt". Every further touch
restarts the timer; `lock_sleep_after_s` after the last touch the overlay closes and the
dashboard asks the service to put the display to sleep now (same path as MQTT `sleep`; the
lock stays, so the next touch after waking shows the overlay again). The layer sits below the
popup layer, so popups opened by automations (browser_mod popups such as the alarm keypad,
notifications, more-info, bubble-card pop-ups opened by `trigger_entity`) appear above it and stay
usable, and the back key still closes them. Hardware buttons are not affected. When the entity
turns `off` the overlay disappears at once; while it is `unavailable`/`unknown` (or HA is not
connected) the last known state is kept.

The sleep request is a package-restricted broadcast (`GuestLockContract.ACTION_SLEEP`) to a
not-exported receiver of the service (the dashboard runs in its own process).

## Adding a card type

1. Implement `CardRenderer` (see the KDoc in `cards/CardRenderer.kt`):

   ```kotlin
   class MyCard(private val config: JsonObject) : CardRenderer {
       private val entityId = config.string("entity")          // parse once

       override fun gridOptions() = GridOptions(columns = 6, rows = 1)   // like getGridOptions()

       @Composable
       override fun Content(ctx: CardContext, modifier: Modifier) {
           val entity = ctx.entity(entityId)                      // subscribes + observes
           HaCard(modifier.haActions(ctx, config)) {              // card chrome + tap/hold/double tap
               if (entity != null) BasicText(ctx.formatter.formatState(entity, ctx.now()))
           }
       }
   }
   ```

2. Register it in `cards/CardCatalog.kt`: `registry.register("custom:my-card") { MyCard(it) }`.

Useful building blocks:

- `ctx.entity(id)` / `ctx.entityState(id)` / `ctx.WatchEntities(ids)`: entity states.
- `ctx.template(template, variables)`: live `render_template` (plain strings pass through).
- `ctx.hass.watchAllEntities()` + `ctx.hass.allStates`: all states (only for auto-entities style cards).
- `ctx.RenderCard(config)`: nested cards (stacks, pop-ups). Use `ctx.withLayout(CardLayout.STACK)` for children.
- `ctx.rows.create(rowConfig)`: entities-card rows; custom rows implement `RowRenderer` and register in `CardCatalog.registerRows`.
- `Modifier.haActions(ctx, config, defaultTap, entityIdSubstitution)`: actions; `ctx.actions.perform(action)` / `callService(...)`.
- `ctx.popups.HashPopup("#hash") { … }` from `CardRenderer.Effects` for bubble-card pop-ups; `ctx.popups.show { HaDialog(…) { … } }` for dialogs; `ctx.popups.showMoreInfo(id)`.
- `ctx.hass.history(...)`, `ctx.hass.forecast(entityId, "daily")`, `ctx.hass.subscribe(message)`, `ctx.hass.command(message)`.
- `RemoteImage(ctx.images, url, refreshKey = …)` for pictures and refreshing camera images.
- `MdiIcon("mdi:lightbulb", color)`, `StateBadge`, `TileIcon`, `GenericEntityRow`, `HaSwitch`, `HaButton`, `HaIconButton`, `HaSlider`, `Markdown(text)`.
- `HaTheme.colors / typography / dimens` and `CssColors.computeCssColor(...)` / `CssColors.stateColor(entity)`.

Return `false` from `isVisible(ctx)` to hide a card and free its grid cell (e.g. empty
auto-entities). Never throw from `Content`; show a `WarningCard` for bad configs.

## Tests

```sh
./gradlew :dashboard:testDebugUnitTest
```

Unit tests cover the camera WebRTC signaling, offer reduction and retry policy, the entity diff store, the subscription planner, the client protocol (against a
fake HA WebSocket server), grid placement/sizing, grid options, markdown, colors and time
formatting. An opt-in, read-only live test connects to a real instance:

```sh
./gradlew :dashboard:testDebugUnitTest --tests '*LiveHassTest*' \
    -Dha.live=true -Dha.url=https://ha.example -Dha.token=… [-Dha.dashboard=url_path]
```
