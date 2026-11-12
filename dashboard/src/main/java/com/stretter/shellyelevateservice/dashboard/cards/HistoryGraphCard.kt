package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stretter.shellyelevateservice.dashboard.format.StateFormatter
import com.stretter.shellyelevateservice.dashboard.hass.ConnectionState
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.hass.HistoryPoint
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.double
import com.stretter.shellyelevateservice.dashboard.json.int
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.CssColors
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * `hui-history-graph-card`: a timeline (`state-history-chart-timeline`) for non-numeric entities and
 * step line charts (`state-history-chart-line`) per unit for numeric ones, drawn on a Canvas.
 * History comes from `history/history_during_period`, refetched every 5 minutes and after
 * reconnects; live state changes are appended in between and the axis follows "now".
 * Statistics merging, zoom, tooltips and `split_device_classes` are not implemented.
 */
class HistoryGraphCard(private val config: JsonObject) : CardRenderer {
    @Immutable
    private data class GraphEntity(val entityId: String, val name: String?, val color: String?)

    private val entities: List<GraphEntity> = config["entities"].let { e ->
        (e as? kotlinx.serialization.json.JsonArray)?.mapNotNull { item ->
            item.asString()?.let { GraphEntity(it, null, null) }
                ?: item.asObject()?.let { o -> o.string("entity")?.let { GraphEntity(it, o.string("name"), o.string("color")) } }
        } ?: emptyList()
    }
    private val entityIds = entities.map { it.entityId }
    private val hoursToShow = config.double("hours_to_show")?.takeIf { it > 0 } ?: 24.0
    private val title = config.string("title")
    private val showNames = config.bool("show_names") != false
    private val columns = config.obj("grid_options")?.int("columns")
    private val narrow = columns == null || columns <= 12
    private val minY = config.double("min_y_axis")
    private val maxY = config.double("max_y_axis")

    override fun gridOptions() = GridOptions(columns = 12, minColumns = 6, minRows = 2)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        if (entities.isEmpty()) {
            WarningCard("You must include at least one entity").Content(ctx, modifier)
            return
        }
        val c = HaTheme.colors
        val t = HaTheme.typography
        val f = ctx.formatter
        val german = f.language == "de"
        val windowMs = (hoursToShow * 3_600_000).toLong()

        var history by remember(entityIds, windowMs) { mutableStateOf<Map<String, List<HistoryPoint>>?>(null) }
        var error by remember(entityIds, windowMs) { mutableStateOf<String?>(null) }
        val connection by ctx.hass.connectionState.collectAsState()
        val connected = connection is ConnectionState.Connected
        LaunchedEffect(entityIds, windowMs, connected) {
            if (!connected) return@LaunchedEffect
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    history = ctx.hass.history(entityIds, now - windowMs)
                    error = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (history == null) error = e.message ?: e.toString()
                }
                delay(REFRESH_MS)
            }
        }

        val now = ctx.now()
        val start = now - windowMs
        // Live states (subscribes the entities); appended to the fetched history.
        val live = entities.map { e -> key(e.entityId) { ctx.entity(e.entityId) } }

        HaCard(modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                if (!title.isNullOrEmpty()) {
                    BasicText(
                        title,
                        style = t.cardHeader.copy(color = c.primaryText),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
                    )
                }
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    val data = history
                    when {
                        error != null -> InfoLine("${if (german) "Fehler beim Laden des Verlaufs" else "Error loading history"}: $error")
                        data == null -> InfoLine(if (german) "Zustandsverlauf wird geladen …" else "Loading state history…")
                        else -> {
                            val charts = remember(data, live, start, now) { buildCharts(data, live, start, now, f) }
                            if (charts.timeline.isEmpty() && charts.lines.isEmpty()) {
                                InfoLine(if (german) "Kein Zustandsverlauf gefunden." else "No state history found.")
                            } else {
                                if (charts.timeline.isNotEmpty()) {
                                    Spacer(Modifier.height(16.dp)) // --timeline-top-margin
                                    TimelineChart(charts.timeline, start, now, f)
                                }
                                charts.lines.forEachIndexed { index, chart ->
                                    if (index > 0 || charts.timeline.isNotEmpty()) {
                                        Spacer(Modifier.height(16.dp))
                                        Box(Modifier.fillMaxWidth().height(2.dp).background(c.divider))
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    LineChart(chart, start, now, f)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Immutable
    private class TimelineRow(val entityId: String, val name: String, val bars: List<Bar>)

    @Immutable
    private class Bar(val start: Long, val end: Long, val label: String, val color: Color?)

    @Immutable
    private class LineSeries(val name: String, val color: Color, val points: List<Pair<Long, Double?>>, val value: String?)

    @Immutable
    private class LineChartData(val unit: String, val series: List<LineSeries>)

    @Immutable
    private class Charts(val timeline: List<TimelineRow>, val lines: List<LineChartData>)

    private fun buildCharts(
        data: Map<String, List<HistoryPoint>>,
        live: List<EntityState?>,
        start: Long,
        end: Long,
        f: StateFormatter,
    ): Charts {
        val timeline = ArrayList<TimelineRow>()
        val lineGroups = LinkedHashMap<String, MutableList<LineSeries>>()
        var colorIndex = 0
        entities.forEachIndexed { i, e ->
            val current = live[i]
            val fetched = data[e.entityId].orEmpty()
            val points = HistoryData.appendLive(HistoryData.trim(fetched, start), current).ifEmpty {
                // `limitedHistoryFromStateObj`: entities without recorded history show their current state.
                current?.let { listOf(HistoryPoint(it.state, it.lastUpdated)) } ?: emptyList()
            }
            if (points.isEmpty()) return@forEachIndexed
            val name = e.name ?: current?.friendlyName ?: e.entityId
            if (HistoryData.isNumeric(e.entityId, current, fetched)) {
                val unit = current?.unit ?: " "
                val color = e.color?.let { CssColors.computeCssColor(it) } ?: TimelineColors.graphColor(colorIndex++)
                lineGroups.getOrPut(unit) { ArrayList() } +=
                    LineSeries(name, color, HistoryData.numericPoints(points), current?.let { f.formatState(it) })
            } else {
                val bars = HistoryData.timelineSegments(points, start, end).map { s ->
                    Bar(s.start, s.end, current?.let { f.translateState(it, s.state) } ?: s.state, TimelineColors.color(s.state, current))
                }
                timeline += TimelineRow(e.entityId, name, bars)
            }
        }
        return Charts(timeline, lineGroups.map { (unit, series) -> LineChartData(unit.trim(), series) })
    }

    @Composable
    private fun InfoLine(text: String) {
        val t = HaTheme.typography
        BasicText(
            text,
            style = t.style(t.sizeM, lineHeight = 60f / 14f, color = HaTheme.colors.secondaryText).copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /** `state-history-chart-timeline`: 30px rows, 20px bars, names left, time axis below. */
    @Composable
    private fun TimelineChart(rows: List<TimelineRow>, start: Long, end: Long, f: StateFormatter) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val measurer = rememberTextMeasurer(cacheSize = 32)
        val textStyle = t.style(t.sizeS, lineHeight = 1f, color = c.primaryText)
        val locale = f.locale
        Canvas(Modifier.fillMaxWidth().height((rows.size * ROW_HEIGHT + GRID_BOTTOM).dp)) {
            val labelMargin = 5.dp.toPx()
            val maxLabel = (if (narrow) 105 else 185).dp.toPx()
            val labelWidth = if (showNames) {
                rows.maxOf { r -> if (r.name.isEmpty()) 0f else minOf(measurer.measure(r.name, textStyle).size.width + labelMargin, maxLabel) }
            } else 0f
            val plotLeft = labelWidth
            val plotRight = size.width - 1.dp.toPx()
            val plotTop = 10.dp.toPx()
            val plotBottom = size.height - GRID_BOTTOM.dp.toPx()
            val band = (plotBottom - plotTop) / rows.size
            val barHeight = 20.dp.toPx()
            val padding = 4.dp.toPx()
            val plotWidth = plotRight - plotLeft

            rows.forEachIndexed { i, row ->
                val center = plotTop + band * (i + 0.5f)
                if (showNames && row.name.isNotEmpty()) {
                    val layout = measurer.measure(
                        row.name, textStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
                        constraints = Constraints(maxWidth = (labelWidth - labelMargin).toInt().coerceAtLeast(0)),
                    )
                    drawText(layout, topLeft = Offset(labelWidth - labelMargin - layout.size.width, center - layout.size.height / 2f))
                }
                clipRect(plotLeft, plotTop, plotRight, plotBottom) {
                    for (bar in row.bars) {
                        val color = bar.color ?: continue
                        val x1 = plotLeft + HistoryData.x(bar.start, start, end, plotWidth)
                        val x2 = plotLeft + HistoryData.x(bar.end, start, end, plotWidth)
                        val top = center - barHeight / 2f
                        drawRect(color, Offset(x1, top), Size(x2 - x1, barHeight))
                        val label = bar.label.replace('\n', ' ')
                        val layout = measurer.measure(label, textStyle.copy(color = TimelineColors.textColor(color)), maxLines = 1)
                        if (layout.size.width < x2 - x1 - padding * 2) {
                            drawText(layout, topLeft = Offset(x1 + padding, center - layout.size.height / 2f))
                        }
                    }
                }
            }
            drawTimeAxis(measurer, textStyle, c.divider, start, end, plotLeft, plotRight, plotBottom, locale, ticks = true)
        }
    }

    /** `state-history-chart-line`: step lines (1.5px), value axis with split lines, legend below. */
    @Composable
    private fun LineChart(chart: LineChartData, start: Long, end: Long, f: StateFormatter) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val measurer = rememberTextMeasurer(cacheSize = 32)
        val textStyle = t.style(t.sizeS, lineHeight = 1f, color = c.primaryText)
        val locale = f.locale
        val values = remember(chart) { chart.series.flatMap { s -> s.points.mapNotNull { it.second } } }
        val ticks = remember(values, minY, maxY) {
            val lo = minOf(values.minOrNull() ?: 0.0, minY ?: Double.MAX_VALUE)
            val hi = maxOf(values.maxOrNull() ?: 1.0, maxY ?: -Double.MAX_VALUE)
            HistoryData.valueTicks(lo, hi)
        }
        Column(Modifier.fillMaxWidth()) {
            // Height max(width / 2, 200) capped at 350 (`_getDefaultHeight`, --chart-max-height),
            // via a layout modifier so intrinsic measurement works.
            Canvas(Modifier.fillMaxWidth().chartHeight()) {
                val labels = ticks.map { measurer.measure(f.formatNumber(it, 2), textStyle) }
                val margin = 5.dp.toPx()
                val plotLeft = (labels.maxOfOrNull { it.size.width } ?: 0) + margin
                val plotRight = size.width - 1.dp.toPx()
                val plotTop = 15.dp.toPx()
                val plotBottom = size.height - 20.dp.toPx()
                val lo = ticks.firstOrNull() ?: 0.0
                val hi = ticks.lastOrNull() ?: 1.0
                fun y(v: Double) = (plotBottom - (v - lo) / (hi - lo).coerceAtLeast(1e-9) * (plotBottom - plotTop)).toFloat()
                ticks.forEachIndexed { i, v ->
                    val yy = y(v)
                    drawLine(c.divider, Offset(plotLeft, yy), Offset(plotRight, yy), 1f)
                    val l = labels[i]
                    drawText(l, topLeft = Offset(plotLeft - margin - l.size.width, yy - l.size.height / 2f))
                }
                if (chart.unit.isNotEmpty()) {
                    val unit = measurer.measure(chart.unit, textStyle)
                    drawText(unit, topLeft = Offset(plotLeft, (plotTop - 2.dp.toPx() - unit.size.height).coerceAtLeast(0f)))
                }
                drawTimeAxis(measurer, textStyle, c.divider, start, end, plotLeft, plotRight, plotBottom, locale, ticks = false, splitTop = plotTop)
                val width = plotRight - plotLeft
                clipRect(plotLeft, 0f, plotRight, size.height) {
                    for (s in chart.series) drawStepLine(s, start, end, plotLeft, width, ::y)
                }
            }
            if (showNames) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    chart.series.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(s.color, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            BasicText(s.name, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.value != null) {
                                Spacer(Modifier.width(4.dp))
                                BasicText(s.value, style = textStyle.copy(color = c.secondaryText), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DrawScope.drawStepLine(s: LineSeries, start: Long, end: Long, left: Float, width: Float, y: (Double) -> Float) {
        val path = Path()
        var open = false
        var lastY = 0f
        for ((time, value) in s.points) {
            val x = left + HistoryData.x(time.coerceAtLeast(start), start, end, width)
            if (value == null) {
                if (open) path.lineTo(x, lastY)
                open = false
                continue
            }
            val yy = y(value)
            if (!open) {
                path.moveTo(x, yy)
                open = true
            } else {
                // step: "end" — hold the previous value until the change.
                path.lineTo(x, lastY)
                path.lineTo(x, yy)
            }
            lastY = yy
        }
        if (open) path.lineTo(left + width, lastY)
        drawPath(path, s.color, style = Stroke(width = 1.5.dp.toPx()))
    }

    /**
     * Time axis below [plotBottom]: tick marks (timeline) or vertical split lines (line chart), and
     * labels centered on the ticks, hiding labels that would overlap the previous one
     * (`hideOverlap`). Midnight is labelled with the date in bold (`formatTimeLabel`).
     */
    private fun DrawScope.drawTimeAxis(
        measurer: TextMeasurer,
        style: TextStyle,
        lineColor: Color,
        start: Long,
        end: Long,
        left: Float,
        right: Float,
        plotBottom: Float,
        locale: Locale,
        ticks: Boolean,
        splitTop: Float = 0f,
    ) {
        val width = right - left
        var lastRight = Float.NEGATIVE_INFINITY
        val gap = 8.dp.toPx()
        for (tick in HistoryData.timeTicks(start, end)) {
            val x = left + HistoryData.x(tick.time, start, end, width)
            if (ticks) drawLine(lineColor, Offset(x, plotBottom), Offset(x, plotBottom + 5.dp.toPx()), 1f)
            else drawLine(lineColor, Offset(x, splitTop), Offset(x, plotBottom), 1f)
            val text = if (tick.midnight) HistoryData.dateLabel(tick.time, locale) else HistoryData.timeLabel(tick.time, locale)
            val layout: TextLayoutResult = measurer.measure(text, if (tick.midnight) style.copy(fontWeight = FontWeight.Bold) else style)
            val x0 = x - layout.size.width / 2f
            if (x0 < lastRight + 4.dp.toPx() || x0 < 0f || x0 + layout.size.width > size.width) continue
            drawText(layout, topLeft = Offset(x0, plotBottom + gap))
            lastRight = x0 + layout.size.width
        }
    }

    private companion object {
        const val ROW_HEIGHT = 30
        const val GRID_BOTTOM = 30
        const val REFRESH_MS = 5 * 60_000L
    }
}

private fun Modifier.chartHeight(): Modifier = layout { measurable, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val height = maxOf(width / 2, 200.dp.roundToPx()).coerceAtMost(350.dp.roundToPx())
        .coerceIn(constraints.minHeight, if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}
