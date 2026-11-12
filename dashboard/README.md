# Native Lovelace dashboard (`:dashboard`)

A native (Kotlin + Jetpack Compose) renderer for a Home Assistant dashboard, built for the Shelly
Wall Display XL whose WebView is too slow for the HA frontend. It loads the dashboard config live
from HA (`lovelace/config`) and renders every card type natively, looking like the web frontend
(default theme, dark mode, frontend `20261028.5`). Unknown card types render a placeholder that
shows the type name.

## Configure and launch

The connection is stored in the SharedPreferences `dashboard` of the `:dashboard` process and set
via adb (the receiver is protected by `android.permission.DUMP`, which only the shell holds):

```sh
adb shell am broadcast -n com.stretter.shellyelevateservice/.dashboard.DashboardConfigReceiver \
    -a com.stretter.shellyelevateservice.dashboard.CONFIGURE \
    --es url http://192.168.1.20:8123 \
    --es token 'eyJ…long-lived-token…' \
    --es dashboard wohnbereich-walldisplay

adb shell am start -n com.stretter.shellyelevateservice/.dashboard.DashboardActivity
```

All extras are optional; an empty value clears a setting. `--es scale 0.75` zooms the UI
(1 CSS px = `scale` dp; default 1, which reproduces the web layout on a 1280×800 mdpi screen).
`--es return_home_after_s 60` sets how long (seconds without a touch) the dashboard stays on
another view before returning to the first one (default 120, `0` = never).
A running dashboard applies changes immediately. The activity runs in its own process
(`:dashboard`), so a crash never takes the background service down; after a crash it restarts
itself (at most 3 times in 5 minutes).

To keep the dashboard in front with the service's app watchdog (instead of the HA app), set
`watchdogPackage` to `dashboard`; see "Native dashboard as watchdog target" in the main README:

```sh
adb shell am broadcast -n com.stretter.shellyelevateservice/.AdbSettingReceiver \
    -a com.stretter.shellyelevateservice.SET_SETTING --es key watchdogPackage --es value dashboard
```

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
| `cards` | `CardRenderer` API, `CardContext`, `CardRegistry`, `CardCatalog`, `HaCard`, `StateBadge`, core cards |
| `rows` | `RowRenderer` API, `RowRegistry`, `GenericEntityRow` and the core rows |
| `actions` | `ActionHandler`, `Modifier.haActions`, `Navigator` |
| `popup` | `PopupHost`, `HashPopup`, `HaDialog`, browser_mod popup, more-info dialog, toasts |
| `browsermod` | browser_mod protocol, `BrowserMod` client, `hass.callService` heartbeat parser |
| `html` | HTML subset parser and renderer, CSS value helpers |
| `theme` | `HaTheme` tokens (colors, typography, dimensions) and `CssColors` (CSS colors, state colors) |
| `icons` | MDI webfont (`assets/mdi.ttf`) + name map (`assets/mdi-map.txt`), `MdiIcon` |
| `markdown` | Markdown subset parser and renderer (`ha-markdown` look) |
| `format`, `image`, `ui`, `json` | formatting, image loading (bitmaps and SVG via AndroidSVG), controls (`HaSwitch`, `HaButton`, `LoopingVideo`, …), lenient JSON accessors |
| `custom` | HACS cards: `button-card` (+ tiny JS-template evaluator, no JS engine), `auto-entities` (filter/sort engine, registry on demand via `get_entries`), `mushroom-template-card`, `template-entity-row`, `timer-bar-card` |

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
next/previous view: `swipe_amount` (% of the width, default 15), `wrap`, `animate` (`swipe`: the
view follows the finger and the next one slides in; `fade`/`flip`: fade in; `none`), subviews and
`visible: false` views are skipped. The swipe only starts when no card consumed the movement
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

### Hardware buttons

HA automations stay the source of truth for the four hardware buttons (MQTT `button_event`,
browser_mod popups). For instant feedback the service also sends a package-restricted broadcast
(`ButtonFeedbackContract.ACTION`, extras `button` 1–4 and `event` `single`/`double`/`long`) to a
not-exported receiver the dashboard registers only while the activity is started (no effect, and
no process start, when the dashboard is not running). The dashboard shows a small pill at the top
center ("Taste 2", "Taste 2 · 2×", "Taste 2 · lang") for 1.5 s, or until the next browser_mod
popup arrives. A single press is reported after the 300 ms double-click window, a long press after
500 ms.

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

Unit tests cover the entity diff store, the subscription planner, the client protocol (against a
fake HA WebSocket server), grid placement/sizing, grid options, markdown, colors and time
formatting. An opt-in, read-only live test connects to a real instance:

```sh
./gradlew :dashboard:testDebugUnitTest --tests '*LiveHassTest*' \
    -Dha.live=true -Dha.url=https://ha.example -Dha.token=… [-Dha.dashboard=url_path]
```
