package com.stretter.shellyelevateservice.dashboard.cards.vacuum

import androidx.compose.runtime.Immutable
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.json.asArray
import com.stretter.shellyelevateservice.dashboard.json.asDouble
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.double
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.objects
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale
import kotlin.math.abs

/*
 * Pure logic of the xiaomi-vacuum-map-card port (no Compose): calibration transforms, room
 * selections, the Roborock segment-clean service call, icon conditions/menus and tile values.
 * Mirrors lovelace-xiaomi-vacuum-map-card v2.4.1.
 */

@Immutable
data class MapPoint(val x: Double, val y: Double)

@Immutable
data class CalibrationPoint(val vacuum: MapPoint, val map: MapPoint)

/** `calibration_points` (camera attribute or config): exactly 3 (affine) or 4 (perspective) points. */
fun parseCalibrationPoints(element: JsonElement?): List<CalibrationPoint>? {
    val points = element.asArray()?.mapNotNull { e ->
        val o = e.asObject() ?: return@mapNotNull null
        val v = o.obj("vacuum") ?: return@mapNotNull null
        val m = o.obj("map") ?: return@mapNotNull null
        CalibrationPoint(
            MapPoint(v.double("x") ?: return@mapNotNull null, v.double("y") ?: return@mapNotNull null),
            MapPoint(m.double("x") ?: return@mapNotNull null, m.double("y") ?: return@mapNotNull null),
        )
    } ?: return null
    return points.takeIf { it.size == 3 || it.size == 4 }
}

/**
 * Vacuum ↔ map image pixel transform (`CoordinatesConverter`): affine from 3 calibration points,
 * perspective (homography) from 4. Map coordinates are pixels of the original image.
 */
class CoordinatesConverter private constructor(
    private val toMap: (Double, Double) -> MapPoint,
    private val toVacuum: (Double, Double) -> MapPoint,
) {
    fun vacuumToMap(x: Double, y: Double): MapPoint = toMap(x, y)
    fun mapToVacuum(x: Double, y: Double): MapPoint = toVacuum(x, y)

    companion object {
        /** Null if the points are invalid or degenerate (collinear). */
        fun create(points: List<CalibrationPoint>?): CoordinatesConverter? {
            if (points == null) return null
            val vacuum = points.map { it.vacuum }
            val map = points.map { it.map }
            return when (points.size) {
                3 -> {
                    val forward = affine(vacuum, map) ?: return null
                    val inverse = affine(map, vacuum) ?: return null
                    CoordinatesConverter(forward, inverse)
                }
                4 -> {
                    val forward = perspective(vacuum, map) ?: return null
                    val inverse = perspective(map, vacuum) ?: return null
                    CoordinatesConverter(forward, inverse)
                }
                else -> null
            }
        }

        /** Solves `[x' y'] = [a b c; d e f] · [x y 1]` from three point pairs. */
        private fun affine(from: List<MapPoint>, to: List<MapPoint>): ((Double, Double) -> MapPoint)? {
            val m = Array(3) { i -> doubleArrayOf(from[i].x, from[i].y, 1.0) }
            val abc = solve(m, DoubleArray(3) { to[it].x }) ?: return null
            val def = solve(m, DoubleArray(3) { to[it].y }) ?: return null
            return { x, y -> MapPoint(abc[0] * x + abc[1] * y + abc[2], def[0] * x + def[1] * y + def[2]) }
        }

        /** 8-parameter homography from four point pairs (like the `perspective-transform` package). */
        private fun perspective(from: List<MapPoint>, to: List<MapPoint>): ((Double, Double) -> MapPoint)? {
            val a = Array(8) { DoubleArray(8) }
            val b = DoubleArray(8)
            for (i in 0 until 4) {
                val (x, y) = from[i]
                val (u, v) = to[i]
                a[i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * u, -y * u)
                b[i] = u
                a[i + 4] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * v, -y * v)
                b[i + 4] = v
            }
            val h = solve(a, b) ?: return null
            return { x, y ->
                val w = h[6] * x + h[7] * y + 1
                MapPoint((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
            }
        }

        /** Gaussian elimination with partial pivoting; null for singular systems. */
        internal fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
            val n = rhs.size
            val a = Array(n) { matrix[it].copyOf() }
            val b = rhs.copyOf()
            for (col in 0 until n) {
                val pivot = (col until n).maxByOrNull { abs(a[it][col]) } ?: return null
                if (abs(a[pivot][col]) < 1e-12) return null
                a[col] = a[pivot].also { a[pivot] = a[col] }
                b[col] = b[pivot].also { b[pivot] = b[col] }
                for (row in col + 1 until n) {
                    val f = a[row][col] / a[col][col]
                    if (f == 0.0) continue
                    for (k in col until n) a[row][k] -= f * a[col][k]
                    b[row] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (row in n - 1 downTo 0) {
                var sum = b[row]
                for (k in row + 1 until n) sum -= a[row][k] * x[k]
                x[row] = sum / a[row][row]
            }
            return x
        }
    }
}

/** One `predefined_selections` entry of a `vacuum_clean_segment` map mode. */
@Immutable
data class RoomSelection(
    /** Segment id as configured (number or string). */
    val id: JsonPrimitive,
    val icon: String?,
    /** Icon position in vacuum coordinates. */
    val iconPosition: MapPoint?,
    /** Outline polygon in vacuum coordinates (may be empty). */
    val outline: List<MapPoint>,
    val label: String?,
) {
    val key: String get() = id.content
}

fun parseRooms(mode: JsonObject): List<RoomSelection> = mode.objects("predefined_selections").mapNotNull { o ->
    val id = o["id"] as? JsonPrimitive ?: return@mapNotNull null
    val icon = o.obj("icon")
    val iconPos = icon?.let { i -> i.double("x")?.let { x -> i.double("y")?.let { y -> MapPoint(x, y) } } }
    val outline = o["outline"].asArray()?.mapNotNull { p ->
        val pair = p.asArray() ?: return@mapNotNull null
        val x = pair.getOrNull(0).asDouble() ?: return@mapNotNull null
        val y = pair.getOrNull(1).asDouble() ?: return@mapNotNull null
        MapPoint(x, y)
    } ?: emptyList()
    RoomSelection(id, icon?.string("name"), iconPos, outline, o.obj("label")?.string("text"))
}

/** A service call ready for `HassClient.callService`. */
@Immutable
data class VacuumServiceCall(val domain: String, val service: String, val data: JsonObject)

/** Map mode templates of the "Roborock" platform that this port supports. */
object RoborockTemplates {
    const val CLEAN_SEGMENT = "vacuum_clean_segment"
    const val MAX_REPEATS = 3

    /**
     * `vacuum_clean_segment` of the Roborock platform template:
     * `vacuum.send_command` with `command: app_segment_clean` and
     * `params: [{segments: [[selection]], repeat: [[repeats]]}]`; ids are numbers (`id_type: number`).
     */
    fun segmentClean(entityId: String, segments: List<JsonPrimitive>, repeats: Int): VacuumServiceCall {
        val ids = JsonArray(segments.map { id -> id.intOrNull?.let { JsonPrimitive(it) } ?: id.doubleOrNull?.let { JsonPrimitive(it.toInt()) } ?: id })
        val params = JsonArray(listOf(JsonObject(mapOf("segments" to ids, "repeat" to JsonPrimitive(repeats)))))
        return VacuumServiceCall(
            "vacuum", "send_command",
            JsonObject(mapOf("command" to JsonPrimitive("app_segment_clean"), "params" to params, "entity_id" to JsonPrimitive(entityId))),
        )
    }

    /** The repeats button cycles 1 → 2 → … → max → 1. */
    fun nextRepeats(current: Int, max: Int = MAX_REPEATS): Int = current % max + 1

    /** Mode name (`map_mode.vacuum_clean_segment`) in the card's language. */
    fun segmentModeName(locale: Locale = Locale.getDefault()): String = if (locale.language == "de") "Räume" else "Rooms"
}

/** Ray casting point-in-polygon test (polygon given as vertices, implicitly closed). */
fun pointInPolygon(x: Double, y: Double, polygon: List<MapPoint>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val pi = polygon[i]
        val pj = polygon[j]
        if ((pi.y > y) != (pj.y > y) && x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x) inside = !inside
        j = i
    }
    return inside
}

// ---------------------------------------------------------------- icons

/**
 * `conditions` of an icon: every condition must hold. A condition compares the entity state (or
 * `attribute`) loosely (`==`) with `value`, or requires it to differ from `value_not`.
 */
fun conditionsMet(conditions: List<JsonObject>, lookup: (String) -> EntityState?): Boolean = conditions.all { c ->
    val entityId = c.string("entity")
    val entity = entityId?.let(lookup)
    val actual: String? = when {
        entityId == null -> ""
        entity == null -> return@all false
        c.string("attribute") != null -> entity.attr(c.string("attribute")!!).let { it.asString() ?: it?.toString() }
        else -> entity.state
    }
    val value = c.string("value")
    val valueNot = c.string("value_not")
    when {
        !value.isNullOrEmpty() -> looselyEqual(actual, value)
        !valueNot.isNullOrEmpty() -> !looselyEqual(actual, valueNot)
        else -> false
    }
}

private fun looselyEqual(a: String?, b: String): Boolean {
    if (a == b) return true
    val x = a?.toDoubleOrNull() ?: return false
    return x == b.toDoubleOrNull()
}

/** One entry of the icons row: a plain icon or a menu (icons sharing a `menu_id`). */
@Immutable
sealed interface IconItem {
    data class Single(val config: JsonObject) : IconItem
    data class Menu(val menuId: String, val entries: List<MenuEntry>) : IconItem {
        /** The entry shown collapsed: the first one whose conditions hold. */
        val selected: MenuEntry get() = entries.first { it.selected }
    }
}

@Immutable
data class MenuEntry(val config: JsonObject, val selected: Boolean)

/**
 * `preprocessIcons`: plain icons are shown when their conditions hold; icons with a `menu_id` are
 * grouped into one menu at the position of its first entry, and a menu without any matching entry
 * is dropped.
 */
fun preprocessIcons(icons: List<JsonObject>, lookup: (String) -> EntityState?): List<IconItem> {
    val items = mutableListOf<Any>()
    val menuIndex = HashMap<String, Int>()
    for (icon in icons) {
        val met = conditionsMet(icon.objects("conditions"), lookup)
        val menuId = icon.string("menu_id")
        if (menuId == null) {
            if (met) items += IconItem.Single(icon)
        } else {
            val index = menuIndex.getOrPut(menuId) { items.add(mutableListOf<MenuEntry>()); items.size - 1 }
            @Suppress("UNCHECKED_CAST")
            (items[index] as MutableList<MenuEntry>) += MenuEntry(icon, met)
        }
    }
    return items.mapNotNull { item ->
        when (item) {
            is IconItem -> item
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                val entries = item as List<MenuEntry>
                if (entries.none { it.selected }) null
                else IconItem.Menu(entries.first().config.string("menu_id")!!, entries.toList())
            }
            else -> null
        }
    }
}

/** Entity ids referenced by icon conditions (they must be subscribed). */
fun conditionEntities(icons: List<JsonObject>): Set<String> =
    icons.flatMap { icon -> icon.objects("conditions").mapNotNull { it.string("entity") } }.toSet()

// ---------------------------------------------------------------- tiles

/**
 * Value text of a tile (`getTileValue`): with `multiplier`/`precision` the raw state or attribute
 * is scaled and formatted with `toFixed`; otherwise HA's formatted state/attribute is used and the
 * entity's unit is stripped when the tile has its own. `translations` (lower-case keys) replace the
 * value; the tile `unit` is appended (`%` with the locale's blank).
 *
 * @param formatted HA's formatted state (or attribute value when the tile has `attribute`).
 */
fun tileValue(tile: JsonObject, entity: EntityState?, formatted: String?, percentWithBlank: Boolean): String {
    val unit = tile.string("unit")?.let { if (it == "%") (if (percentWithBlank) " %" else "%") else " $it" } ?: ""
    val multiplier = tile.double("multiplier")
    val precision = tile.double("precision")?.toInt()
    val scaled = multiplier != null || precision != null
    val attribute = tile.string("attribute")
    var value: String = ""
    if (entity != null && tile.string("entity") != null) {
        if (scaled) {
            value = (if (attribute != null) entity.attr(attribute).let { it.asString() ?: it?.toString() } else entity.state) ?: "null"
        } else {
            value = formatted ?: ""
            val entityUnit = entity.unit
            if (unit.isNotEmpty() && !entityUnit.isNullOrEmpty() && value.endsWith(entityUnit)) {
                value = value.dropLast(entityUnit.length).trimEnd()
            }
        }
    }
    if (scaled) {
        value.trim().toDoubleOrNull()?.let { number ->
            val result = number * (multiplier ?: 1.0)
            value = if (precision != null) String.format(Locale.US, "%.${precision.coerceIn(0, 20)}f", result) else jsNumber(result)
        }
    }
    tile.obj("translations")?.get(value.lowercase())?.asString()?.let { value = it }
    return value + unit
}

/** JavaScript's `Number.prototype.toString` for typical values (no trailing `.0`). */
private fun jsNumber(d: Double): String = if (d == Math.floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()

/**
 * Icon of a tile (`getIcon`): `icon_source` (`entity` or `entity.attributes.attr`), else `icon`,
 * else the entity's `icon` attribute. Null means "an empty icon slot" (the web card still keeps
 * the 5px gap), an empty string no slot at all.
 */
fun tileIcon(tile: JsonObject, lookup: (String) -> EntityState?): String? {
    tile.string("icon_source")?.let { source ->
        val parts = source.split(".attributes.")
        val entity = lookup(parts[0]) ?: return null
        return if (parts.size == 2) entity.attrString(parts[1]) else entity.state
    }
    if (tile.containsKey("icon")) return tile.string("icon")
    return tile.string("entity")?.let(lookup)?.icon
}

/** Entity ids a tile reads. */
fun tileEntities(tiles: List<JsonObject>): Set<String> = tiles.flatMap { tile ->
    listOfNotNull(tile.string("entity"), tile.string("icon_source")?.substringBefore(".attributes."))
}.toSet()

/** Greedy flex-wrap line breaking: indices of the items on each line for the given [bases]. */
fun flexLines(bases: List<Int>, maxWidth: Int, gap: Int): List<List<Int>> {
    val lines = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    var used = 0
    bases.forEachIndexed { i, basis ->
        if (current.isNotEmpty() && used + gap + basis > maxWidth) {
            lines += current
            current = mutableListOf()
            used = 0
        }
        used += if (current.isEmpty()) basis else gap + basis
        current += i
    }
    if (current.isNotEmpty()) lines += current
    return lines
}
