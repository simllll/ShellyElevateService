package io.github.simllll.panelelevate.dashboard.format

import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Formats entity states the way the frontend does (`formatEntityState`): backend state
 * translations (`frontend/get_translations`, category `entity_component`), locale number format,
 * unit spacing, timestamps; plus relative times (`relativeTime`).
 *
 * Translations are Compose state: text formatted during composition updates once they load.
 */
@Stable
class StateFormatter {
    var language: String by mutableStateOf(Locale.getDefault().language)
        private set
    private var translations: Map<String, String> by mutableStateOf(emptyMap())

    val locale: Locale get() = Locale.forLanguageTag(language)

    fun setTranslations(language: String, entityComponent: Map<String, String>) {
        this.language = language
        translations = entityComponent
    }

    /** Raw translation lookup, e.g. `component.cover.entity_component._.state.open`. */
    fun translate(key: String): String? = translations[key]

    /** Translated state without unit (`Geöffnet`, `An`, …); the raw state if there is no translation. */
    fun translateState(entity: EntityState, state: String = entity.state): String {
        val domain = entity.domain
        if (state == EntityState.UNAVAILABLE || state == EntityState.UNKNOWN) {
            translations["component.$domain.entity_component._.state.$state"]?.let { return it }
            // The frontend's own strings (state.default.*); only German and English are bundled.
            val german = language == "de"
            return if (state == EntityState.UNAVAILABLE) (if (german) "Nicht verfügbar" else "Unavailable")
            else (if (german) "Unbekannt" else "Unknown")
        }
        entity.deviceClass?.let { dc ->
            translations["component.$domain.entity_component.$dc.state.$state"]?.let { return it }
        }
        translations["component.$domain.entity_component._.state.$state"]?.let { return it }
        return state
    }

    /** Full state text as `<state-display>` shows it, e.g. `21,5 °C`, `Geöffnet`, `vor 5 Minuten`. */
    fun formatState(entity: EntityState, nowMs: Long = System.currentTimeMillis()): String {
        val state = entity.state
        if (state == EntityState.UNAVAILABLE || state == EntityState.UNKNOWN) return translateState(entity)
        val domain = entity.domain
        if ((domain == "sensor" && entity.deviceClass == "timestamp") || domain in TIMESTAMP_DOMAINS) {
            parseIso(state)?.let { return if (domain == "sensor") relativeTime(it, nowMs, capitalize = true) else formatDateTime(it) }
        }
        if (domain == "input_datetime") return formatInputDatetime(entity)
        val unit = entity.unit
        val number = state.toDoubleOrNull()
        if (number != null && domain in NUMERIC_DOMAINS && entity.deviceClass != "enum") {
            return formatNumber(state) + unitSuffix(unit)
        }
        return translateState(entity) + if (unit != null && number != null) unitSuffix(unit) else ""
    }

    /** Formats a number string keeping its precision, with locale separators (`21.50` → `21,50`). */
    fun formatNumber(value: String): String {
        val number = value.toDoubleOrNull() ?: return value
        val decimals = value.substringAfter('.', "").takeIf { value.contains('.') && !value.contains('e', true) }?.length ?: 0
        val format = DecimalFormat("#,##0" + if (decimals > 0) "." + "0".repeat(decimals) else "", DecimalFormatSymbols.getInstance(locale))
        return format.format(number)
    }

    fun formatNumber(value: Double, maxDecimals: Int = 2): String {
        val format = DecimalFormat("#,##0." + "#".repeat(maxDecimals.coerceAtLeast(0)), DecimalFormatSymbols.getInstance(locale))
        return format.format(value)
    }

    /** `blankBeforeUnit`: a space before units, none before `°`; `%` gets one in locales that use it. */
    fun unitSuffix(unit: String?): String = when {
        unit.isNullOrEmpty() -> ""
        unit == "°" -> unit
        unit == "%" -> if (language in BLANK_BEFORE_PERCENT) " %" else "%"
        else -> " $unit"
    }

    /**
     * Relative time like `<ha-relative-time>` (`relativeTime` with numeric "auto"):
     * "vor 4 Stunden", "gestern", "in 5 Minuten". [capitalize] uppercases the first letter
     * (entity rows use the capitalized form).
     */
    fun relativeTime(fromMs: Long, nowMs: Long = System.currentTimeMillis(), capitalize: Boolean = false): String {
        val (value, unit) = selectUnit(fromMs, nowMs)
        val text = formatRelative(value, unit)
        return if (capitalize) text.replaceFirstChar { it.titlecase(locale) } else text
    }

    private fun formatRelative(value: Long, unit: TimeUnitKind): String {
        val formatter = relativeFormatter()
        val absolute = when (unit) {
            TimeUnitKind.SECOND -> AbsoluteUnit.NOW.takeIf { value == 0L }
            TimeUnitKind.DAY -> AbsoluteUnit.DAY
            TimeUnitKind.WEEK -> AbsoluteUnit.WEEK
            TimeUnitKind.MONTH -> AbsoluteUnit.MONTH
            TimeUnitKind.YEAR -> AbsoluteUnit.YEAR
            else -> null
        }
        if (absolute != null) {
            when {
                absolute == AbsoluteUnit.NOW -> return formatter.format(Direction.PLAIN, AbsoluteUnit.NOW)
                value == -1L -> return formatter.format(Direction.LAST, absolute)
                value == 1L -> return formatter.format(Direction.NEXT, absolute)
                value == 0L -> return formatter.format(Direction.THIS, absolute)
            }
        }
        val relative = when (unit) {
            TimeUnitKind.SECOND -> RelativeUnit.SECONDS
            TimeUnitKind.MINUTE -> RelativeUnit.MINUTES
            TimeUnitKind.HOUR -> RelativeUnit.HOURS
            TimeUnitKind.DAY -> RelativeUnit.DAYS
            TimeUnitKind.WEEK -> RelativeUnit.WEEKS
            TimeUnitKind.MONTH -> RelativeUnit.MONTHS
            TimeUnitKind.YEAR -> RelativeUnit.YEARS
        }
        return formatter.format(abs(value).toDouble(), if (value < 0) Direction.LAST else Direction.NEXT, relative)
    }

    private var cachedRelative: Pair<String, RelativeDateTimeFormatter>? = null

    private fun relativeFormatter(): RelativeDateTimeFormatter {
        cachedRelative?.takeIf { it.first == language }?.let { return it.second }
        return RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale)).also { cachedRelative = language to it }
    }

    fun formatDateTime(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(ms))

    fun formatTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(ms))

    private fun formatInputDatetime(entity: EntityState): String {
        val hasDate = entity.attributes["has_date"]?.toString() == "true"
        val hasTime = entity.attributes["has_time"]?.toString() == "true"
        val state = entity.state
        return when {
            hasDate && hasTime -> parseIso(state.replace(' ', 'T'))?.let { formatDateTime(it) } ?: state
            hasDate -> parseIso(state)?.let { DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(it)) } ?: state
            hasTime -> state.split(':').take(2).joinToString(":")
            else -> state
        }
    }

    companion object {
        private val TIMESTAMP_DOMAINS = setOf("button", "input_button", "scene", "event")
        private val NUMERIC_DOMAINS = setOf("sensor", "number", "input_number", "counter")
        private val BLANK_BEFORE_PERCENT = setOf("cs", "de", "fi", "fr", "sk", "sv")
    }
}
