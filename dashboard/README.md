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
A running dashboard applies changes immediately. The activity runs in its own process
(`:dashboard`), so a crash never takes the background service down; after a crash it restarts
itself (at most 3 times in 5 minutes).

## Architecture

```
DashboardActivity ─ DashboardScreen ─ SectionsView ─ GridSection ─ CardRenderer.Content
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
| `lovelace` | `SectionsView`, `GridSection`, `CardGrid`/`SectionsGrid`/`StretchRow` layouts, `GridMath` (CSS grid placement + track sizing) |
| `cards` | `CardRenderer` API, `CardContext`, `CardRegistry`, `CardCatalog`, `HaCard`, `StateBadge`, core cards |
| `rows` | `RowRenderer` API, `RowRegistry`, `GenericEntityRow` and the core rows |
| `actions` | `ActionHandler`, `Modifier.haActions`, `Navigator` |
| `popup` | `PopupHost`, `HashPopup`, `HaDialog`, more-info dialog |
| `theme` | `HaTheme` tokens (colors, typography, dimensions) and `CssColors` (CSS colors, state colors) |
| `icons` | MDI webfont (`assets/mdi.ttf`) + name map (`assets/mdi-map.txt`), `MdiIcon` |
| `markdown` | Markdown subset parser and renderer (`ha-markdown` look) |
| `format`, `image`, `ui`, `json` | formatting, image loading, controls (`HaSwitch`, `HaButton`, …), lenient JSON accessors |

### Layout fidelity

`SectionsView` follows `hui-sections-view`: columns of 320–500px (`max_columns`, default 4),
32px column gap (8px below 600px width), 24px row gap and padding, `column_span`, `row_span`,
`dense_section_placement`. `GridSection` follows `hui-grid-section`: 12 × span columns, 56px rows,
8px gaps, CSS auto-placement; cards with numeric `rows` get exactly
`rows × 64 − 8` px, auto cards their content height, stretched to the row (CSS
`align-items: stretch`, via intrinsic measurement). Sizes come from `CardRenderer.gridOptions()`
merged with the config's `grid_options` (legacy `layout_options` are migrated).

### Performance

Only entities on screen are subscribed (`subscribe_entities` with `entity_ids`, extended
incrementally when a popup opens). Every entity has its own `StateFlow`; a card reading
`ctx.entity(id)` recomposes only when that entity changes. Templates are shared and replayed
(`renderTemplate` is cached per template + variables). No WebView, no Material dependency.

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
