package com.stretter.shellyelevateservice.dashboard.cards.vacuum

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.stretter.shellyelevateservice.dashboard.actions.ActionTrigger
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.cards.CardHeader
import com.stretter.shellyelevateservice.dashboard.cards.CardRenderer
import com.stretter.shellyelevateservice.dashboard.cards.HaCard
import com.stretter.shellyelevateservice.dashboard.cards.WatchEntities
import com.stretter.shellyelevateservice.dashboard.cards.WarningCard
import com.stretter.shellyelevateservice.dashboard.cards.entity
import com.stretter.shellyelevateservice.dashboard.cards.entityState
import com.stretter.shellyelevateservice.dashboard.cards.now
import com.stretter.shellyelevateservice.dashboard.cards.rememberLoaded
import com.stretter.shellyelevateservice.dashboard.hass.ConnectionState
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.int
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.objects
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import com.stretter.shellyelevateservice.dashboard.ui.PressIndication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max

/**
 * `custom:xiaomi-vacuum-map-card` (v2.4.1) for the Roborock platform: map image from
 * `map_source.camera` with calibration from the camera's `calibration_points` (or the config),
 * the `vacuum_clean_segment` mode (tap rooms, ×1/×2/×3 repeats, play runs `app_segment_clean`
 * immediately), the lock / pinch-zoom of the map, the conditional icons row with menus and the
 * tiles. Zone and go-to modes are not supported.
 */
class VacuumMapCard(private val config: JsonObject) : CardRenderer {
    private val entityId = config.string("entity")
    private val title = config.string("title")
    private val cameraId = config.obj("map_source")?.string("camera")
    private val staticImage = config.obj("map_source")?.string("image")
    private val calibrationFromCamera = config.obj("calibration_source")?.bool("camera") == true
    private val configCalibration = parseCalibrationPoints(config.obj("calibration_source")?.get("calibration_points"))
    private val twoFingerPan = config.bool("two_finger_pan") == true
    private val segmentMode = config.objects("map_modes").firstOrNull { it.string("template") == RoborockTemplates.CLEAN_SEGMENT }
    private val rooms = segmentMode?.let { parseRooms(it) } ?: emptyList()
    private val maxRepeats = segmentMode?.int("max_repeats") ?: RoborockTemplates.MAX_REPEATS
    private val icons = config.objects("icons")
    private val tiles = config.objects("tiles")
    private val watched = (conditionEntities(icons) + tileEntities(tiles) + listOfNotNull(entityId)).toList()

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        if (entityId == null) {
            WarningCard("xiaomi-vacuum-map-card: entity fehlt").Content(ctx, modifier)
            return
        }
        ctx.WatchEntities(watched)
        HaCard(modifier.fillMaxWidth(), clip = true) {
            Column(Modifier.fillMaxWidth()) {
                if (!title.isNullOrEmpty()) CardHeader(title)
                val selection = remember { mutableStateListOf<RoomSelection>() }
                var repeats by remember { mutableIntStateOf(1) }
                MapArea(ctx, selection)
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (segmentMode != null) {
                        ControlsRow(
                            repeats = repeats,
                            onRepeats = { repeats = RoborockTemplates.nextRepeats(repeats, maxRepeats) },
                            onRun = { run(ctx, selection.map { it.id }, repeats) },
                        )
                    }
                    IconsRow(ctx)
                    if (tiles.isNotEmpty()) Tiles(ctx)
                }
            }
        }
    }

    private fun run(ctx: CardContext, segments: List<JsonPrimitive>, repeats: Int) {
        if (segments.isEmpty()) {
            ctx.popups.toast("Keine Räume ausgewählt")
            return
        }
        val call = RoborockTemplates.segmentClean(entityId!!, segments, repeats)
        ctx.popups.toast("Befehl gesendet")
        ctx.actions.callService(call.domain, call.service, call.data, null)
    }

    // ------------------------------------------------------------------ map

    @Composable
    private fun MapArea(ctx: CardContext, selection: MutableList<RoomSelection>) {
        val c = HaTheme.colors
        val camera by ctx.entityState(cameraId)
        val cam = camera
        val calibration = if (calibrationFromCamera) parseCalibrationPoints(cam?.attr("calibration_points")) else configCalibration
        val converter = remember(calibration) { CoordinatesConverter.create(calibration) }
        // Rooms in map image pixels (independent of zoom and display size).
        val mapRooms = remember(converter) {
            converter?.let { conv ->
                rooms.map { r ->
                    MapRoom(r, r.iconPosition?.let { conv.vacuumToMap(it.x, it.y) }, r.outline.map { conv.vacuumToMap(it.x, it.y) })
                }
            } ?: emptyList()
        }

        var size by remember { mutableStateOf(IntSize.Zero) }
        var image by remember { mutableStateOf<MapImage?>(null) }
        val url = cam?.entityPicture ?: staticImage
        // Reload when the image entity reports a new map (its state is the last update time).
        val refreshKey = cam?.state ?: staticImage
        if (url != null && size.width > 0) {
            LaunchedEffect(refreshKey, size.width) {
                loadMapImage(ctx.hass, url, size.width)?.let { image = it }
            }
        }

        var locked by remember { mutableStateOf(config.bool("map_locked") == true) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val img = image
        val aspect = img?.let { it.naturalWidth.toFloat() / it.naturalHeight.coerceAtLeast(1) } ?: 1f
        val density = LocalDensity.current
        val iconWrapper = 36.dp
        val iconHalfPx = with(density) { (iconWrapper / 2).toPx() }

        fun displayFactor(): Float = img?.let { size.width.toFloat() / it.naturalWidth } ?: 0f
        fun toScreen(p: MapPoint): Offset {
            val k = displayFactor()
            return Offset((p.x * k).toFloat() * scale + offset.x, (p.y * k).toFloat() * scale + offset.y)
        }

        fun onTap(tap: Offset) {
            if (img == null || segmentMode == null) return
            val hit = mapRooms.lastOrNull { room ->
                room.icon?.let { (toScreen(it) - tap).getDistance() <= iconHalfPx } == true
            } ?: mapRooms.lastOrNull { room ->
                room.outline.size >= 3 && pointInPolygon(tap.x.toDouble(), tap.y.toDouble(), room.outline.map { p -> toScreen(p).let { MapPoint(it.x.toDouble(), it.y.toDouble()) } })
            } ?: return
            if (!selection.remove(hit.room)) selection.add(hit.room)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .background(c.secondaryBackground)
                .clipToBounds()
                .onSizeChanged { size = it }
                .pointerInput(mapRooms, img, segmentMode) { detectTapGestures(onTap = ::onTap) }
                .then(
                    if (locked) Modifier
                    else Modifier.pointerInput(twoFingerPan) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.count { it.pressed }
                                if (fingers >= 2 || (!twoFingerPan && fingers == 1)) {
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                    offset = centroid - (centroid - offset) * (newScale / scale) + pan
                                    scale = newScale
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                ),
        ) {
            if (img != null) {
                Image(
                    bitmap = img.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
                RoomOutlines(mapRooms, selection, ::toScreen)
                mapRooms.forEach { room ->
                    val pos = room.icon ?: return@forEach
                    val icon = room.room.icon ?: return@forEach
                    val selected = room.room in selection
                    Box(
                        Modifier
                            .offset { toScreen(pos).let { IntOffset((it.x - iconHalfPx).toInt(), (it.y - iconHalfPx).toInt()) } }
                            .size(iconWrapper)
                            .clip(CircleShape)
                            .background(if (selected) c.primary else MapColors.secondary),
                        contentAlignment = Alignment.Center,
                    ) {
                        MdiIcon(icon, if (selected) c.primaryText else MapColors.secondaryText)
                    }
                }
            }
            if (img != null && converter == null && segmentMode != null) {
                BasicText(
                    "Kalibrierung fehlt",
                    style = HaTheme.typography.style(color = c.warning),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // Lock (top right) and zoom controls (bottom right, only while unlocked).
            MapButton(if (locked) "mdi:lock" else "mdi:lock-open", Modifier.align(Alignment.TopEnd).padding(5.dp)) { locked = !locked }
            if (!locked) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MapColors.secondary),
                ) {
                    MapButton("mdi:image-filter-center-focus") { scale = 1f; offset = Offset.Zero }
                    MapButton("mdi:magnify-minus") { zoomAt(size, 1 / ZOOM_STEP, scale, offset) { s, o -> scale = s; offset = o } }
                    MapButton("mdi:magnify-plus") { zoomAt(size, ZOOM_STEP, scale, offset) { s, o -> scale = s; offset = o } }
                }
            }
        }
    }

    /** Zooms around the center of the map. */
    private inline fun zoomAt(size: IntSize, factor: Float, scale: Float, offset: Offset, apply: (Float, Offset) -> Unit) {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val center = Offset(size.width / 2f, size.height / 2f)
        apply(newScale, center - (center - offset) * (newScale / scale))
    }

    @Composable
    private fun RoomOutlines(rooms: List<MapRoom>, selection: List<RoomSelection>, toScreen: (MapPoint) -> Offset) {
        val density = LocalDensity.current
        val stroke = remember(density) {
            with(density) {
                Stroke(
                    width = 1.dp.toPx(),
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 5.dp.toPx())),
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            rooms.forEach { room ->
                if (room.outline.size < 2) return@forEach
                val path = Path().apply {
                    room.outline.forEachIndexed { i, p -> toScreen(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                    close()
                }
                if (room.room in selection) drawPath(path, MapColors.outlineFillSelected)
                drawPath(path, Color.White, style = stroke)
            }
        }
    }

    @Composable
    private fun MapButton(icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Box(
            modifier
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MapColors.secondary)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = remember { PressIndication(MapColors.ripple, 0.4f) },
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            MdiIcon(icon, MapColors.secondaryText)
        }
    }

    // ------------------------------------------------------------------ controls

    @Composable
    private fun ControlsRow(repeats: Int, onRepeats: () -> Unit, onRun: () -> Unit) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val modeIcon = segmentMode?.string("icon") ?: "mdi:floor-plan"
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Mode selector (only the segment mode is available, so it does not open a menu).
            Box(Modifier.size(50.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                MdiIcon(modeIcon, c.primaryText)
            }
            BasicText(
                segmentMode?.string("name") ?: RoborockTemplates.segmentModeName(),
                style = t.style(t.sizeM, color = c.primaryText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 15.dp).weight(1f),
            )
            Row(Modifier.clip(RoundedCornerShape(25.dp)).background(MapColors.secondary)) {
                Box(Modifier.size(50.dp).pressable(onRepeats), contentAlignment = Alignment.Center) {
                    BasicText("×$repeats", style = t.style(t.sizeM, color = MapColors.secondaryText))
                }
                Box(
                    Modifier.size(50.dp).clip(RoundedCornerShape(25.dp)).background(c.primary).pressable(onRun),
                    contentAlignment = Alignment.Center,
                ) {
                    MdiIcon("mdi:play", c.primaryText)
                    // The web card overlays the mode icon at half size, up and to the right.
                    MdiIcon(modeIcon, c.primaryText, Modifier.offset(x = 7.5.dp, y = (-10).dp), size = 12.dp)
                }
            }
        }
    }

    @Composable
    private fun IconsRow(ctx: CardContext) {
        if (icons.isEmpty()) return
        // Reads every condition entity, so the row recomposes when one of them changes.
        val lookup = conditionEntities(icons).associateWith { ctx.entity(it) }
        val items = preprocessIcons(icons) { lookup[it] }
        if (items.isEmpty()) return
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(Modifier.clip(RoundedCornerShape(25.dp)).background(MapColors.secondary)) {
                items.forEach { item ->
                    when (item) {
                        is IconItem.Single -> IconButton(item.config.string("icon")) { tap(ctx, item.config) }
                        is IconItem.Menu -> IconMenu(ctx, item)
                    }
                }
            }
        }
    }

    /** Icon/menu actions go through `handleAction` with the card's vacuum as default entity (more-info). */
    private fun tap(ctx: CardContext, icon: JsonObject) {
        val withEntity = if (icon.containsKey("entity")) icon else JsonObject(icon + ("entity" to JsonPrimitive(entityId)))
        ctx.actions.handle(withEntity, ActionTrigger.TAP)
    }

    @Composable
    private fun IconButton(icon: String?, selected: Boolean = false, onClick: () -> Unit) {
        val c = HaTheme.colors
        Box(
            Modifier
                .size(50.dp)
                .then(if (selected) Modifier.clip(RoundedCornerShape(25.dp)).background(c.primary) else Modifier)
                .pressable(onClick),
            contentAlignment = Alignment.Center,
        ) {
            MdiIcon(icon, if (selected) c.primaryText else MapColors.secondaryText)
        }
    }

    /** `xvmc-dropdown-menu` for icons sharing a `menu_id`: collapsed shows the matching entry. */
    @Composable
    private fun IconMenu(ctx: CardContext, menu: IconItem.Menu) {
        var open by remember { mutableStateOf(false) }
        val t = HaTheme.typography
        val c = HaTheme.colors
        Box {
            IconButton(menu.selected.config.string("icon")) { open = true }
            if (open) {
                Popup(
                    alignment = Alignment.TopCenter,
                    offset = with(LocalDensity.current) { IntOffset(0, 54.dp.roundToPx()) },
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        Modifier
                            .width(260.dp)
                            .clip(RoundedCornerShape(25.dp))
                            .background(MapColors.secondary),
                    ) {
                        menu.entries.forEach { entry ->
                            val bg = if (entry.selected) Modifier.clip(RoundedCornerShape(25.dp)).background(c.primary) else Modifier
                            val fg = if (entry.selected) c.primaryText else MapColors.secondaryText
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .then(bg)
                                    .pressable {
                                        open = false
                                        tap(ctx, entry.config)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) { MdiIcon(entry.config.string("icon"), fg) }
                                BasicText(
                                    entry.config.string("label") ?: entry.config.string("tooltip") ?: "",
                                    style = t.style(t.sizeM, color = fg),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 10.dp, end = 15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ tiles

    @Composable
    private fun Tiles(ctx: CardContext) {
        val connection by ctx.hass.connectionState.collectAsState()
        val language = ctx.formatter.language
        val connected = connection == ConnectionState.Connected
        val translations = rememberLoaded(language, connected) {
            if (connected) EntityTranslations.load(ctx.hass, language, tiles.mapNotNull { it.string("entity") }.distinct()) else null
        } ?: EntityTranslations.EMPTY
        FlexWrap(gap = 5.dp, minItemWidth = 100.dp) {
            tiles.forEach { tile -> Tile(ctx, tile, translations) }
        }
    }

    @Composable
    private fun Tile(ctx: CardContext, tile: JsonObject, translations: EntityTranslations) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val entity = ctx.entity(tile.string("entity"))
        val iconSourceId = tile.string("icon_source")?.substringBefore(".attributes.")
        val iconSource = ctx.entity(iconSourceId)
        val now = ctx.now()
        val formatted = entity?.let { e ->
            val attribute = tile.string("attribute")
            if (attribute != null) formatTileAttribute(ctx.formatter, translations, e, attribute)
            else formatTileState(ctx.formatter, translations, e, now)
        }
        val value = tileValue(tile, entity, formatted, percentWithBlank = ctx.formatter.unitSuffix("%") == " %")
        val icon = tileIcon(tile) { id -> if (id == iconSourceId) iconSource else if (id == entity?.entityId) entity else null }
        val label = tile.string("label") ?: entity?.friendlyName ?: tile.string("entity").orEmpty()
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(c.secondaryBackground)
                .haActions(ctx, tile)
                .padding(10.dp),
        ) {
            BasicText(label, style = t.style(11.67.sp, color = c.primaryText, lineHeight = 1.2f), maxLines = 1)
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.Bottom) {
                if (icon != "") {
                    if (icon != null) MdiIcon(icon, c.primaryText)
                    Box(Modifier.width(5.dp))
                }
                BasicText(value, style = t.style(t.sizeM, color = c.primaryText, lineHeight = 1.4f), maxLines = 1)
            }
        }
    }

    private class MapRoom(val room: RoomSelection, val icon: MapPoint?, val outline: List<MapPoint>)

    private companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 8f
        const val ZOOM_STEP = 1.5f
    }
}

/** The card's own colors (`--map-card-internal-*` with HA's default theme). */
private object MapColors {
    /** `--slider-secondary-color` in the dark default theme. */
    val secondary = Color(0xFFB3E5FC)
    /** `--text-light-primary-color`. */
    val secondaryText = Color(0xFF212121)
    val ripple = Color(0xFF7A7F87)
    val outlineFillSelected = Color(0x4DFFFFFF)
}

@Composable
private fun Modifier.pressable(onClick: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = remember { PressIndication(MapColors.ripple, 0.4f) },
    onClick = onClick,
)

/**
 * CSS `display: flex; flex-wrap: wrap; gap; align-items: stretch` with every item `flex-grow: 1`
 * and a basis of `max(minItemWidth, max-content)` (the tiles' `width: 80px; min-width: fit-content`).
 */
@Composable
private fun FlexWrap(gap: Dp, minItemWidth: Dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val maxWidth = constraints.maxWidth
        val bases = measurables.map { max(minItemWidth.roundToPx(), it.maxIntrinsicWidth(Constraints.Infinity)).coerceAtMost(maxWidth) }
        val lines = flexLines(bases, maxWidth, gapPx)
        val placements = ArrayList<Triple<androidx.compose.ui.layout.Placeable, Int, Int>>(measurables.size)
        var y = 0
        lines.forEachIndexed { lineIndex, line ->
            val used = line.sumOf { bases[it] } + gapPx * (line.size - 1)
            val extra = (maxWidth - used).coerceAtLeast(0)
            val widths = line.mapIndexed { i, index -> bases[index] + extra / line.size + if (i < extra % line.size) 1 else 0 }
            val height = line.indices.maxOf { i -> measurables[line[i]].minIntrinsicHeight(widths[i]) }
            var x = 0
            line.forEachIndexed { i, index ->
                val p = measurables[index].measure(Constraints.fixed(widths[i], height))
                placements += Triple(p, x, y)
                x += widths[i] + gapPx
            }
            y += height + if (lineIndex < lines.lastIndex) gapPx else 0
        }
        layout(maxWidth, y) { placements.forEach { (p, px, py) -> p.place(px, py) } }
    }
}
