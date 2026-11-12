package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.hass.HassClient
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.json.asArray
import com.stretter.shellyelevateservice.dashboard.json.asDouble
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.objects
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The selected energy period, shared by the energy cards of the dashboard (the frontend's default
 * energy collection). Only day periods are supported: [dayStart] is local midnight.
 */
object EnergySelection {
    var dayStart by mutableLongStateOf(startOfDay(System.currentTimeMillis()))
        private set

    fun shift(days: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_MONTH, days) }
        dayStart = startOfDay(cal.timeInMillis)
    }

    fun today() {
        dayStart = startOfDay(System.currentTimeMillis())
    }

    fun isToday(): Boolean = dayStart == startOfDay(System.currentTimeMillis())

    fun startOfDay(ms: Long, zone: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(zone).apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun endOfDay(dayStart: Long): Long = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
}

/**
 * `energy-date-selection` (`hui-energy-period-selector`), simplified to days: the selected day,
 * previous/next arrows and "Heute". Week/month/year periods and the compare toggle are omitted.
 */
class EnergyDateSelectionCard(@Suppress("UNUSED_PARAMETER") config: JsonObject) : CardRenderer {
    override fun gridOptions() = GridOptions(columns = 12, rows = 1)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val locale = ctx.formatter.locale
        val day = EnergySelection.dayStart
        ctx.now() // re-evaluates "today" after midnight
        HaCard(modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    SimpleDateFormat("EEEE, d. MMMM yyyy", locale).format(Date(day)),
                    style = t.style(t.sizeL, FontWeight.Medium, color = c.primaryText),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton("mdi:chevron-left") { EnergySelection.shift(-1) }
                IconButton("mdi:chevron-right") { EnergySelection.shift(1) }
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(enabled = !EnergySelection.isToday()) { EnergySelection.today() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BasicText(
                        if (locale.language == "de") "Heute" else "Today",
                        style = t.style(t.sizeM, FontWeight.Medium, color = if (EnergySelection.isToday()) c.disabledText else c.primary),
                    )
                }
            }
        }
    }

    @Composable
    private fun IconButton(icon: String, onClick: () -> Unit) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            MdiIcon(icon, HaTheme.colors.primaryText)
        }
    }
}

/**
 * `energy-devices-detail-graph`: hourly consumption of the energy dashboard's individual devices
 * (`device_consumption`, devices included in another device's stat are left out) for the
 * selected day as stacked bars in the graph palette, plus untracked consumption (grid import minus
 * the devices) in grey, with a legend below. Reloaded every 5 minutes and when the day changes.
 */
class EnergyDevicesDetailGraphCard(private val config: JsonObject) : CardRenderer {
    override fun gridOptions() = GridOptions(columns = 12, minColumns = 6, minRows = 4)

    private class Device(val statId: String, val name: String?, val color: Color)

    private class Data(val devices: List<Device>, val untracked: Boolean, val hours: Map<String, DoubleArray>, val hourCount: Int)

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val day = EnergySelection.dayStart
        var reload by remember { mutableIntStateOf(0) }
        LaunchedEffect(day) {
            while (true) {
                delay(RELOAD_MS)
                reload++
            }
        }
        val data by produceState<Data?>(null, day, reload) { value = load(ctx.hass, day) ?: value }
        val german = ctx.formatter.locale.language == "de"
        HaCard(modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                config.string("title")?.let { CardHeader(it, Modifier.padding(bottom = 0.dp)) }
                val d = data
                if (d == null) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        BasicText(if (german) "Lade …" else "Loading …", style = t.style(t.sizeM, color = c.secondaryText))
                    }
                    return@Column
                }
                val names = d.devices.map { dev -> dev.name ?: ctx.entity(dev.statId)?.friendlyName ?: dev.statId }
                val series = d.devices.map { d.hours[it.statId] ?: DoubleArray(d.hourCount) } +
                    listOfNotNull(d.hours[UNTRACKED]?.takeIf { d.untracked })
                val colors = d.devices.map { it.color } + listOfNotNull(UNTRACKED_COLOR.takeIf { d.untracked })
                Bars(series, colors, d.hourCount, day, ctx)
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val labels = names + listOfNotNull((if (german) "Nicht erfasster Verbrauch" else "Untracked consumption").takeIf { d.untracked })
                    labels.forEachIndexed { i, label ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(colors[i], RoundedCornerShape(2.dp)))
                            BasicText(label, style = t.style(t.sizeS, color = c.primaryText), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Bars(series: List<DoubleArray>, colors: List<Color>, hourCount: Int, day: Long, ctx: CardContext) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val measurer = rememberTextMeasurer(cacheSize = 48)
        val textStyle = t.style(t.sizeS, lineHeight = 1f, color = c.primaryText)
        val totals = DoubleArray(hourCount) { h -> series.sumOf { s -> s.getOrElse(h) { 0.0 }.coerceAtLeast(0.0) } }
        val ticks = HistoryData.valueTicks(0.0, totals.maxOrNull()?.takeIf { it > 0 } ?: 0.1)
        val hourFormat = DateFormat.getTimeInstance(DateFormat.SHORT, ctx.formatter.locale)
        Canvas(Modifier.fillMaxWidth().height(250.dp).padding(16.dp)) {
            val labels = ticks.map { measurer.measure(ctx.formatter.formatNumber(it, 2), textStyle) }
            val margin = 5.dp.toPx()
            val plotLeft = (labels.maxOfOrNull { it.size.width } ?: 0) + margin
            val plotRight = size.width
            val plotTop = 15.dp.toPx()
            val plotBottom = size.height - 20.dp.toPx()
            val hi = ticks.lastOrNull()?.takeIf { it > 0 } ?: 1.0
            fun y(v: Double) = (plotBottom - v / hi * (plotBottom - plotTop)).toFloat()
            ticks.forEachIndexed { i, v ->
                val yy = y(v)
                drawLine(c.divider, Offset(plotLeft, yy), Offset(plotRight, yy), 1f)
                drawText(labels[i], topLeft = Offset(plotLeft - margin - labels[i].size.width, yy - labels[i].size.height / 2f))
            }
            val unit = measurer.measure(UNIT, textStyle)
            drawText(unit, topLeft = Offset(plotLeft, (plotTop - 2.dp.toPx() - unit.size.height).coerceAtLeast(0f)))
            val slot = (plotRight - plotLeft) / hourCount
            val barWidth = slot * 0.7f
            for (h in 0 until hourCount) {
                var base = 0.0
                val x = plotLeft + slot * h + (slot - barWidth) / 2f
                series.forEachIndexed { i, s ->
                    val v = s.getOrElse(h) { 0.0 }.coerceAtLeast(0.0)
                    if (v <= 0) return@forEachIndexed
                    val top = y(base + v)
                    drawRect(colors[i], Offset(x, top), Size(barWidth, y(base) - top))
                    base += v
                }
                if (h % LABEL_EVERY_HOURS == 0) {
                    val label = measurer.measure(hourFormat.format(Date(day + h * HOUR_MS)), textStyle)
                    val lx = (plotLeft + slot * h + slot / 2f - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
                    drawText(label, topLeft = Offset(lx, plotBottom + 4.dp.toPx()))
                }
            }
        }
    }

    companion object {
        private const val UNIT = "kWh"
        private const val UNTRACKED = "__untracked__"
        private val UNTRACKED_COLOR = Color(0xFF8A8A8A)
        private const val HOUR_MS = 3_600_000L
        private const val LABEL_EVERY_HOURS = 4
        private const val RELOAD_MS = 5 * 60_000L

        private suspend fun load(hass: HassClient, dayStart: Long): Data? = try {
            val prefs = hass.command(jsonOf("type" to "energy/get_prefs")).asObject() ?: JsonObject(emptyMap())
            val devices = prefs.objects("device_consumption")
                .filter { it.string("included_in_stat") == null }
                .mapIndexedNotNull { i, d -> d.string("stat_consumption")?.let { Device(it, d.string("name"), TimelineColors.graphColor(i)) } }
            val gridStats = prefs.objects("energy_sources").filter { it.string("type") == "grid" }.flatMap { source ->
                listOfNotNull(source.string("stat_energy_from")) + source.objects("flow_from").mapNotNull { it.string("stat_energy_from") }
            }
            val end = EnergySelection.endOfDay(dayStart)
            val hourCount = ((end - dayStart) / HOUR_MS).toInt() // 23/25 on DST days
            val ids = devices.map { it.statId } + gridStats
            val result = if (ids.isEmpty()) JsonObject(emptyMap()) else hass.command(
                jsonOf(
                    "type" to "recorder/statistics_during_period",
                    "start_time" to iso(dayStart),
                    "end_time" to iso(end),
                    "statistic_ids" to ids,
                    "period" to "hour",
                    "types" to listOf("change"),
                    "units" to jsonOf("energy" to UNIT),
                )
            ).asObject() ?: JsonObject(emptyMap())
            val hours = HashMap<String, DoubleArray>()
            for (id in ids) {
                val arr = DoubleArray(hourCount)
                result[id].asArray()?.forEach { p ->
                    val o = p.asObject() ?: return@forEach
                    val start = o["start"].asDouble()?.toLong() ?: return@forEach
                    val h = ((start - dayStart) / HOUR_MS).toInt()
                    if (h in 0 until hourCount) arr[h] += o["change"].asDouble() ?: 0.0
                }
                hours[id] = arr
            }
            val untracked = gridStats.isNotEmpty()
            if (untracked) {
                hours[UNTRACKED] = DoubleArray(hourCount) { h ->
                    gridStats.sumOf { hours[it]!![h] } - devices.sumOf { hours[it.statId]!![h] }
                }
            }
            Data(devices, untracked, hours, hourCount)
        } catch (e: Exception) {
            android.util.Log.w("EnergyCards", "loading energy statistics failed: ${e.message}")
            null
        }

        private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(ms))
    }
}
