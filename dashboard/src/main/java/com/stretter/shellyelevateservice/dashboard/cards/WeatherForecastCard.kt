package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.int
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToLong

/**
 * `hui-weather-forecast-card`: condition artwork, translated condition, name, temperature with
 * the unit raised beside it, today's high/low (or precipitation/humidity), and a forecast row from
 * `weather/subscribe_forecast`. Size classes (narrow / very-narrow / short) follow the card's
 * ResizeController thresholds. Forecast drag-scrolling is not implemented (5 slots fit).
 */
class WeatherForecastCard(private val config: JsonObject) : CardRenderer {
    private val entityId = config.string("entity")
    private val showCurrent = config.bool("show_current") != false
    private val showForecastConfig = config.bool("show_forecast") != false
    private val forecastType = config.string("forecast_type")
    private val slots = config.int("forecast_slots") ?: 5
    private val roundTemperature = config.bool("round_temperature") == true
    private val secondaryAttribute = config.string("secondary_info_attribute")

    override fun gridOptions(): GridOptions {
        var rows = 1
        var minRows = 1
        if (showCurrent) { rows++; minRows++ }
        if (showForecastConfig) {
            rows++; minRows++
            if (forecastType == "daily") rows++
        }
        return GridOptions(columns = 12, rows = rows, minColumns = if (showCurrent && showForecastConfig) 5 else 4, minRows = minRows)
    }

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        if (entityId == null) {
            WarningCard("Entity must be specified").Content(ctx, modifier)
            return
        }
        val entity = ctx.entity(entityId)
        val t = HaTheme.typography
        val c = HaTheme.colors
        if (entity == null) {
            WarningCard(if (ctx.formatter.language == "de") "Entität nicht gefunden: $entityId" else "Entity not found: $entityId")
                .Content(ctx, modifier)
            return
        }
        if (entity.isUnavailable) {
            val name = config.string("name") ?: ctx.entityDisplayName(entity, entityId)
            HaCard(modifier.haActions(ctx, config)) {
                Box(Modifier.fillMaxWidth().height(100.dp).padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    BasicText(
                        if (ctx.formatter.language == "de") "Entität ist derzeit nicht verfügbar: $name ($entityId)"
                        else "Entity is currently unavailable: $name ($entityId)",
                        style = t.style(t.sizeL, color = c.primaryText).copy(textAlign = TextAlign.Center),
                    )
                }
            }
            return
        }

        val forecast = if (showForecastConfig && forecastType != null && forecastType != "legacy") rememberForecast(ctx, entityId, forecastType) else null
        val items = WeatherForecast.usable(forecast)?.take(slots)
        val showWeather = items == null || showCurrent
        val name = config.string("name") ?: ctx.entityDisplayName(entity, entityId)

        // Size classes from the laid-out card size (like the ResizeController, one frame late);
        // no BoxWithConstraints so the grid can measure the card intrinsically.
        val density = LocalDensity.current
        var size by remember { mutableStateOf(WeatherSize(0, false)) }
        HaCard(
            modifier
                .haActions(ctx, config)
                .onSizeChanged { px -> size = with(density) { WeatherSize.of(px.width.toDp(), px.height.toDp()) } },
        ) {
            Column(
                Modifier.fillMaxSize().padding(vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (showWeather) CurrentWeather(ctx, entity, name, items, size)
                if (items != null) {
                    if (showWeather) Spacer(Modifier.height(if (size.short) 12.dp else if (size.width == 3) 8.dp else 16.dp))
                    ForecastRow(ctx, items, forecastType == "hourly", forecastType == "twice_daily", size)
                }
            }
        }
    }

    @Composable
    private fun CurrentWeather(ctx: CardContext, entity: EntityState, name: String, forecast: List<ForecastItem>?, size: WeatherSize) {
        val t = HaTheme.typography
        val c = HaTheme.colors
        val f = ctx.formatter
        val unit = WeatherForecast.temperatureUnit(entity.attributes)
        val iconSize = when {
            size.width == 3 -> 48.dp
            size.short -> 48.dp
            size.width >= 1 -> 52.dp
            else -> 64.dp
        }
        val bigText: TextUnit = when {
            size.short -> 24.sp
            size.width >= 1 -> t.sizeXl
            else -> t.size3xl
        }
        val unitText: TextUnit = if (size.width >= 1 && !size.short) t.sizeL else t.size2xl
        val stateStyle = t.style(bigText, lineHeight = t.lineHeightCondensed, color = c.primaryText)
        val smallStyle = t.style(t.sizeM, lineHeight = t.lineHeightCondensed, color = c.secondaryText)
        val icon: @Composable () -> Unit = {
            Box(Modifier.widthIn(min = iconSize).padding(end = if (size.width == 3) 0.dp else 16.dp)) {
                WeatherStateIcon(entity.state, iconSize)
            }
        }
        val temperature = entity.attrDouble("temperature")
        val tempMargin = if (size.width >= 1) 16.dp else 24.dp

        val info: @Composable (Modifier) -> Unit = { mod ->
            if (size.width >= 2) {
                // very-narrow: column with state and temperature only
                Column(mod, horizontalAlignment = if (size.width == 3) Alignment.CenterHorizontally else Alignment.Start) {
                    BasicText(f.formatState(entity), style = stateStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TemperatureText(temperature?.let { formatTemp(f, it) }, unit, stateStyle, unitText, tempMargin)
                }
            } else {
                Row(mod) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        BasicText(f.formatState(entity), style = stateStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(name, style = smallStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TemperatureText(temperature?.let { formatTemp(f, it) }, unit, stateStyle, unitText, tempMargin)
                        SecondaryAttribute(ctx, entity, forecast, unit, smallStyle)
                    }
                }
            }
        }
        if (size.width == 3) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                icon()
                info(Modifier.padding(top = 4.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                icon()
                info(Modifier.weight(1f))
            }
        }
    }

    /** `.temp`: number + nbsp, the unit absolutely positioned (smaller, 1px down) in the margin. */
    @Composable
    private fun TemperatureText(value: String?, unit: String, style: androidx.compose.ui.text.TextStyle, unitSize: TextUnit, margin: Dp) {
        val t = HaTheme.typography
        Row {
            BasicText(if (value != null) "$value " else " ", style = style, maxLines = 1)
            Box(Modifier.width(margin).wrapContentWidth(Alignment.Start, unbounded = true)) {
                if (value != null) {
                    BasicText(unit, style = t.style(unitSize, lineHeight = t.lineHeightCondensed, color = style.color), maxLines = 1, modifier = Modifier.offset(y = 1.dp))
                }
            }
        }
    }

    /** `.attribute`: configured attribute, else today's extrema, else precipitation/humidity. */
    @Composable
    private fun SecondaryAttribute(ctx: CardContext, entity: EntityState, forecast: List<ForecastItem>?, unit: String, style: androidx.compose.ui.text.TextStyle) {
        val f = ctx.formatter
        val attr = secondaryAttribute
        if (attr != null) {
            val value = entity.attrDouble(attr)
            val text = if (value != null) formatAttribute(f, entity, attr, value, unit) else entity.attrString(attr).orEmpty()
            AttributeLine(ATTRIBUTE_ICONS[attr], text, style)
            return
        }
        val now = ctx.now()
        val extrema = forecast?.let { WeatherForecast.extrema(it, now) }
        if (extrema != null) {
            val (high, low) = extrema
            val text = buildString {
                if (high != null) append(formatTemp(f, high)).append(f.unitSuffix(unit))
                if (high != null && low != null) append(" / ")
                if (low != null) append(formatTemp(f, low)).append(f.unitSuffix(unit))
            }
            AttributeLine(null, text, style)
            return
        }
        val precipitation = forecast?.firstOrNull()?.precipitation
        when {
            precipitation != null -> AttributeLine(ATTRIBUTE_ICONS["precipitation"], formatAttribute(f, entity, "precipitation", round1(precipitation), unit), style)
            entity.attrDouble("humidity") != null -> AttributeLine(ATTRIBUTE_ICONS["humidity"], formatAttribute(f, entity, "humidity", round1(entity.attrDouble("humidity")!!), unit), style)
            else -> BasicText("", style = style)
        }
    }

    @Composable
    private fun AttributeLine(icon: String?, text: String, style: androidx.compose.ui.text.TextStyle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) MdiIcon(icon, style.color, Modifier.padding(end = 4.dp), size = 20.dp)
            BasicText(text, style = style, maxLines = 1)
        }
    }

    @Composable
    private fun ForecastRow(ctx: CardContext, items: List<ForecastItem>, hourly: Boolean, dayNight: Boolean, size: WeatherSize) {
        val t = HaTheme.typography
        val c = HaTheme.colors
        val f = ctx.formatter
        val iconSize = if (size.short) 32.dp else 40.dp
        val iconPadding = if (size.short) 4.dp else 6.dp
        val labelStyle = t.style(t.sizeS, lineHeight = 1f, color = c.secondaryText).copy(textAlign = TextAlign.Center)
        val tempStyle = t.style(t.sizeL, lineHeight = 1f, color = c.primaryText).copy(textAlign = TextAlign.Center)
        val lowStyle = t.style(t.sizeM, lineHeight = 1.43f, color = c.secondaryText).copy(textAlign = TextAlign.Center)
        val german = f.language == "de"
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceAround) {
            items.forEach { item ->
                if (item.temperature == null && item.templow == null) return@forEach
                Column(
                    Modifier.widthIn(min = 48.dp).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val label = when {
                        dayNight -> if (item.isDaytime != false) (if (german) "Tag" else "Day") else (if (german) "Nacht" else "Night")
                        hourly -> HistoryData.timeLabel(item.datetime, f.locale)
                        else -> WeatherForecast.weekdayShort(item.datetime, f.locale)
                    }
                    BasicText(label, style = labelStyle, maxLines = 1)
                    if (item.condition != null) {
                        WeatherStateIcon(item.condition, iconSize, Modifier.padding(vertical = iconPadding), nightTime = WeatherForecast.isNightTime(item))
                    }
                    BasicText(item.temperature?.let { formatTemp(f, it) + "°" } ?: "—", style = tempStyle, maxLines = 1)
                    val low = item.templow?.let { formatTemp(f, it) + "°" } ?: if (hourly) null else "—"
                    if (low != null) BasicText(low, style = lowStyle, maxLines = 1)
                }
            }
        }
    }

    private fun formatTemp(f: com.stretter.shellyelevateservice.dashboard.format.StateFormatter, value: Double): String =
        if (roundTemperature) f.formatNumber(value.roundToLong().toDouble(), 0) else f.formatNumber(value, 3)

    /** `formatEntityAttributeValue` for numeric weather attributes (`getWeatherUnit`). */
    private fun formatAttribute(
        f: com.stretter.shellyelevateservice.dashboard.format.StateFormatter,
        entity: EntityState,
        attribute: String,
        value: Double,
        temperatureUnit: String,
    ): String {
        val unit = when (attribute) {
            "temperature", "apparent_temperature", "dew_point", "templow" -> temperatureUnit
            "humidity", "cloud_coverage", "precipitation_probability" -> "%"
            "precipitation" -> entity.attrString("precipitation_unit") ?: "mm"
            "pressure" -> entity.attrString("pressure_unit") ?: "hPa"
            "wind_speed", "wind_gust_speed" -> entity.attrString("wind_speed_unit") ?: "km/h"
            "visibility" -> entity.attrString("visibility_unit") ?: "km"
            else -> null
        }
        val number = if (roundTemperature && attribute in TEMPERATURE_ATTRIBUTES) f.formatNumber(value.roundToLong().toDouble(), 0) else f.formatNumber(value, 3)
        return number + f.unitSuffix(unit)
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    private companion object {
        val TEMPERATURE_ATTRIBUTES = setOf("temperature", "apparent_temperature", "dew_point")

        /** `weatherAttrIcons`. */
        val ATTRIBUTE_ICONS = mapOf(
            "apparent_temperature" to "mdi:thermometer",
            "cloud_coverage" to "mdi:weather-cloudy",
            "dew_point" to "mdi:thermometer-water",
            "humidity" to "mdi:water-percent",
            "wind_bearing" to "mdi:weather-windy",
            "wind_speed" to "mdi:weather-windy",
            "wind_gust_speed" to "mdi:weather-windy",
            "pressure" to "mdi:gauge",
            "temperature" to "mdi:thermometer",
            "uv_index" to "mdi:sun-wireless",
            "visibility" to "mdi:weather-fog",
            "precipitation" to "mdi:weather-rainy",
        )
    }
}

/**
 * Size classes of the card's ResizeController. [width]: 0 regular, 1 narrow (<375),
 * 2 very-narrow (<300), 3 very-very-narrow (<180); [short]: height < 235.
 */
private data class WeatherSize(val width: Int, val short: Boolean) {
    companion object {
        fun of(width: Dp, height: Dp?): WeatherSize = WeatherSize(
            width = when {
                width < 180.dp -> 3
                width < 300.dp -> 2
                width < 375.dp -> 1
                else -> 0
            },
            short = height != null && height < 235.dp,
        )
    }
}

/** Subscribes the forecast while composed; retries after errors (e.g. entity not yet loaded). */
@Composable
private fun rememberForecast(ctx: CardContext, entityId: String, type: String): List<ForecastItem>? {
    var forecast by remember(entityId, type) { mutableStateOf<List<ForecastItem>?>(null) }
    LaunchedEffect(entityId, type) {
        while (true) {
            runCatching { ctx.hass.forecast(entityId, type).collect { forecast = WeatherForecast.parse(it) } }
            delay(5_000)
        }
    }
    return forecast
}
