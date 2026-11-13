package io.github.simllll.panelelevate.dashboard.theme

import androidx.compose.ui.graphics.Color
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.hass.isActive

/**
 * CSS color handling: the frontend's color variables (color.globals.ts), theme color names used
 * in card configs (`color: amber`) and parsing of CSS color strings, including `var(--x, fallback)`.
 */
object CssColors {
    /** `--<name>-color` palette (color.globals.ts). */
    val themeColors: Map<String, Color> = mapOf(
        "primary" to HaPalette.Primary40,
        "accent" to Color(0xFFFF9800),
        "disabled" to Color(0xFF464646),
        "red" to Color(0xFFF44336),
        "pink" to Color(0xFFE91E63),
        "purple" to Color(0xFF926BC7),
        "deep-purple" to Color(0xFF6E41AB),
        "indigo" to Color(0xFF3F51B5),
        "blue" to Color(0xFF2196F3),
        "light-blue" to Color(0xFF03A9F4),
        "cyan" to Color(0xFF00BCD4),
        "teal" to Color(0xFF009688),
        "green" to Color(0xFF4CAF50),
        "light-green" to Color(0xFF8BC34A),
        "lime" to Color(0xFFCDDC39),
        "yellow" to Color(0xFFFFEB3B),
        "amber" to Color(0xFFFFC107),
        "orange" to Color(0xFFFF9800),
        "deep-orange" to Color(0xFFFF6F22),
        "brown" to Color(0xFF795548),
        "light-grey" to Color(0xFFBDBDBD),
        "grey" to Color(0xFF9E9E9E),
        "dark-grey" to Color(0xFF606060),
        "blue-grey" to Color(0xFF607D8B),
        "black" to Color(0xFF000000),
        "white" to Color(0xFFFFFFFF),
    )

    private val c = HaColors()

    /** CSS custom properties (without `--`) that cards may reference via `var(...)`. */
    val variables: Map<String, Color> = buildMap {
        themeColors.forEach { (name, color) -> put("$name-color", color) }
        put("primary-background-color", c.primaryBackground)
        put("card-background-color", c.cardBackground)
        put("ha-card-background", c.cardBackground)
        put("secondary-background-color", c.secondaryBackground)
        put("primary-text-color", c.primaryText)
        put("secondary-text-color", c.secondaryText)
        put("disabled-text-color", c.disabledText)
        put("text-primary-color", c.textPrimaryColor)
        put("divider-color", c.divider)
        put("outline-color", c.outline)
        put("state-icon-color", c.stateIcon)
        put("state-active-color", c.stateActive)
        put("state-inactive-color", c.stateInactive)
        put("state-unavailable-color", c.stateUnavailable)
        put("error-color", c.error)
        put("warning-color", c.warning)
        put("success-color", c.success)
        put("info-color", c.info)
        put("dark-primary-color", Color(0xFF0288D1))
        put("light-primary-color", Color(0xFFB3E5FC))
        putAll(stateColorVariables())
    }

    private fun stateColorVariables(): Map<String, Color> {
        val t = themeColors
        fun v(name: String) = t.getValue(name)
        return mapOf(
            "state-alarm_control_panel-armed_away-color" to v("green"),
            "state-alarm_control_panel-armed_custom_bypass-color" to v("green"),
            "state-alarm_control_panel-armed_home-color" to v("green"),
            "state-alarm_control_panel-armed_night-color" to v("green"),
            "state-alarm_control_panel-armed_vacation-color" to v("green"),
            "state-alarm_control_panel-arming-color" to v("orange"),
            "state-alarm_control_panel-disarming-color" to v("orange"),
            "state-alarm_control_panel-pending-color" to v("orange"),
            "state-alarm_control_panel-triggered-color" to v("red"),
            "state-alert-off-color" to v("orange"),
            "state-alert-on-color" to v("red"),
            "state-binary_sensor-active-color" to v("amber"),
            "state-binary_sensor-battery-on-color" to v("red"),
            "state-binary_sensor-carbon_monoxide-on-color" to v("red"),
            "state-binary_sensor-gas-on-color" to v("red"),
            "state-binary_sensor-glass_break-on-color" to v("red"),
            "state-binary_sensor-heat-on-color" to v("red"),
            "state-binary_sensor-lock-on-color" to v("red"),
            "state-binary_sensor-moisture-on-color" to v("red"),
            "state-binary_sensor-problem-on-color" to v("red"),
            "state-binary_sensor-safety-on-color" to v("red"),
            "state-binary_sensor-smoke-on-color" to v("red"),
            "state-binary_sensor-sound-on-color" to v("red"),
            "state-binary_sensor-tamper-on-color" to v("red"),
            "state-climate-auto-color" to v("green"),
            "state-climate-cool-color" to v("blue"),
            "state-climate-dry-color" to v("orange"),
            "state-climate-fan_only-color" to v("cyan"),
            "state-climate-heat-color" to v("deep-orange"),
            "state-climate-heat-cool-color" to v("amber"),
            "state-cover-active-color" to v("purple"),
            "state-device_tracker-active-color" to v("blue"),
            "state-device_tracker-home-color" to v("green"),
            "state-fan-active-color" to v("cyan"),
            "state-humidifier-on-color" to v("blue"),
            "state-lawn_mower-active-color" to v("teal"),
            "state-lawn_mower-error-color" to v("red"),
            "state-light-active-color" to v("amber"),
            "state-lock-jammed-color" to v("red"),
            "state-lock-locked-color" to v("green"),
            "state-lock-locking-color" to v("orange"),
            "state-lock-unlocked-color" to v("red"),
            "state-lock-unlocking-color" to v("orange"),
            "state-lock-open-color" to v("red"),
            "state-lock-opening-color" to v("orange"),
            "state-media_player-active-color" to v("light-blue"),
            "state-person-active-color" to v("blue"),
            "state-person-home-color" to v("green"),
            "state-plant-active-color" to v("red"),
            "state-siren-active-color" to v("red"),
            "state-sun-above_horizon-color" to v("amber"),
            "state-sun-below_horizon-color" to v("indigo"),
            "state-switch-active-color" to v("amber"),
            "state-update-active-color" to v("orange"),
            "state-vacuum-active-color" to v("teal"),
            "state-vacuum-error-color" to v("red"),
            "state-valve-active-color" to v("blue"),
            "state-sensor-battery-high-color" to v("green"),
            "state-sensor-battery-low-color" to v("red"),
            "state-sensor-battery-medium-color" to v("orange"),
            "state-water_heater-eco-color" to v("green"),
            "state-water_heater-electric-color" to v("orange"),
            "state-water_heater-gas-color" to v("orange"),
            "state-water_heater-heat_pump-color" to v("orange"),
            "state-water_heater-high_demand-color" to v("deep-orange"),
            "state-water_heater-performance-color" to v("deep-orange"),
            "state-weather-clear_night-color" to v("deep-purple"),
            "state-weather-cloudy-color" to v("light-grey"),
            "state-weather-exceptional-color" to v("red"),
            "state-weather-fog-color" to v("grey"),
            "state-weather-hail-color" to v("cyan"),
            "state-weather-lightning_rainy-color" to v("lime"),
            "state-weather-lightning-color" to v("yellow"),
            "state-weather-partlycloudy-color" to v("blue-grey"),
            "state-weather-pouring-color" to v("indigo"),
            "state-weather-rainy-color" to v("blue"),
            "state-weather-snowy_rainy-color" to v("light-blue"),
            "state-weather-snowy-color" to Color(0xFFC0E0FF),
            "state-weather-sunny-color" to v("amber"),
            "state-weather-windy_variant-color" to v("green"),
            "state-weather-windy-color" to v("green"),
        )
    }

    private val cssNamed: Map<String, Color> = mapOf(
        "transparent" to Color.Transparent,
        "black" to Color.Black, "white" to Color.White, "red" to Color(0xFFFF0000),
        "green" to Color(0xFF008000), "blue" to Color(0xFF0000FF), "yellow" to Color(0xFFFFFF00),
        "orange" to Color(0xFFFFA500), "purple" to Color(0xFF800080), "grey" to Color(0xFF808080),
        "gray" to Color(0xFF808080), "silver" to Color(0xFFC0C0C0), "lime" to Color(0xFF00FF00),
        "cyan" to Color(0xFF00FFFF), "magenta" to Color(0xFFFF00FF), "pink" to Color(0xFFFFC0CB),
        "gold" to Color(0xFFFFD700), "teal" to Color(0xFF008080), "navy" to Color(0xFF000080),
        "darkgrey" to Color(0xFFA9A9A9), "lightgrey" to Color(0xFFD3D3D3), "brown" to Color(0xFFA52A2A),
    )

    /**
     * Port of `computeCssColor`: a theme color name (`amber`, `deep-orange`, `primary`) or any CSS color.
     */
    fun computeCssColor(value: String?): Color? {
        val v = value?.trim()?.lowercase() ?: return null
        return themeColors[v] ?: parse(v)
    }

    /** Parses `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb()`, `rgba()`, `hsl()`, `var(--x, fallback)` and CSS names. */
    fun parse(value: String?): Color? {
        val v = value?.trim()?.lowercase()?.removeSuffix("!important")?.trim() ?: return null
        if (v.isEmpty() || v == "none" || v == "inherit" || v == "initial") return null
        if (v.startsWith("#")) return parseHex(v.substring(1))
        if (v.startsWith("var(")) {
            val inner = v.removePrefix("var(").removeSuffix(")")
            val name = inner.substringBefore(',').trim().removePrefix("--")
            return variables[name] ?: inner.substringAfter(',', "").takeIf { it.isNotBlank() }?.let { parse(it) }
        }
        if (v.startsWith("rgb")) {
            val nums = v.substringAfter('(').substringBefore(')').split(',', ' ', '/').filter { it.isNotBlank() }
            if (nums.size < 3) return null
            val r = channel(nums[0]) ?: return null
            val g = channel(nums[1]) ?: return null
            val b = channel(nums[2]) ?: return null
            val a = nums.getOrNull(3)?.let { alpha(it) } ?: 1f
            return Color(r, g, b, (a * 255).toInt())
        }
        if (v.startsWith("hsl")) {
            val nums = v.substringAfter('(').substringBefore(')').split(',', ' ', '/').filter { it.isNotBlank() }
            if (nums.size < 3) return null
            val h = nums[0].removeSuffix("deg").toFloatOrNull() ?: return null
            val s = nums[1].removeSuffix("%").toFloatOrNull()?.div(100f) ?: return null
            val l = nums[2].removeSuffix("%").toFloatOrNull()?.div(100f) ?: return null
            val a = nums.getOrNull(3)?.let { alpha(it) } ?: 1f
            return Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), a)
        }
        return cssNamed[v] ?: themeColors[v]
    }

    /** `r, g, b` triple like `rgb_color` attributes. */
    fun fromRgb(r: Int, g: Int, b: Int): Color = Color(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    private fun channel(s: String): Int? =
        if (s.endsWith("%")) s.removeSuffix("%").toFloatOrNull()?.let { (it * 2.55f).toInt() } else s.toFloatOrNull()?.toInt()

    private fun alpha(s: String): Float? =
        if (s.endsWith("%")) s.removeSuffix("%").toFloatOrNull()?.div(100f) else s.toFloatOrNull()

    private fun parseHex(hex: String): Color? {
        val full = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("") + "ff"
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex + "ff"
            8 -> hex
            else -> return null
        }
        val n = full.toLongOrNull(16) ?: return null
        val r = (n shr 24 and 0xFF).toInt()
        val g = (n shr 16 and 0xFF).toInt()
        val b = (n shr 8 and 0xFF).toInt()
        val a = (n and 0xFF).toInt()
        return Color(r, g, b, a)
    }

    private val STATE_COLORED_DOMAINS = setOf(
        "alarm_control_panel", "alert", "automation", "binary_sensor", "calendar", "camera", "climate",
        "cover", "device_tracker", "fan", "group", "humidifier", "input_boolean", "lawn_mower", "light",
        "lock", "media_player", "person", "plant", "remote", "schedule", "script", "siren", "sun",
        "switch", "timer", "update", "vacuum", "valve", "water_heater", "weather",
    )

    /**
     * Port of `stateColorCss`: the state color of an entity, or null when the domain has none
     * (callers then fall back to `--state-icon-color`).
     *
     * [inactiveColor] replaces the generic `--state-inactive-color` fallback: state-badge sets it to
     * `initial`, so inactive entity rows show `--state-icon-color` while tiles show grey.
     */
    fun stateColor(
        entity: EntityState,
        state: String = entity.state,
        inactiveColor: Color? = variables["state-inactive-color"],
    ): Color? {
        if (state == EntityState.UNAVAILABLE) return variables["state-unavailable-color"]
        val domain = entity.domain
        if (domain == "sensor" && entity.deviceClass == "battery") {
            state.toDoubleOrNull()?.let { level ->
                return variables[
                    when {
                        level >= 70 -> "state-sensor-battery-high-color"
                        level >= 30 -> "state-sensor-battery-medium-color"
                        else -> "state-sensor-battery-low-color"
                    }
                ]
            }
        }
        if (domain !in STATE_COLORED_DOMAINS) return null
        for (property in domainColorProperties(domain, entity.deviceClass, state, entity.isActive(state))) {
            if (property == "state-inactive-color") return inactiveColor
            variables[property]?.let { return it }
        }
        return null
    }

    /** CSS variable names tried in order (`domainColorProperties`). */
    fun domainColorProperties(domain: String, deviceClass: String?, state: String, active: Boolean): List<String> {
        val stateKey = state.lowercase().replace(Regex("[^a-z0-9]+"), "_")
        val activeKey = if (active) "active" else "inactive"
        return buildList {
            if (deviceClass != null) add("state-$domain-$deviceClass-$stateKey-color")
            add("state-$domain-$stateKey-color")
            add("state-$domain-$activeKey-color")
            add("state-$activeKey-color")
        }
    }

    /** Brightness filter of light icons (`stateColorBrightness`): 50 % … 100 %. */
    fun brightnessFactor(entity: EntityState): Float? {
        if (entity.domain == "plant") return null
        val brightness = entity.attrDouble("brightness") ?: return null
        return ((brightness + 245) / 5 / 100).toFloat()
    }
}
