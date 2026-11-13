package io.github.simllll.panelelevate.dashboard.custom.buttoncard

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import io.github.simllll.panelelevate.dashboard.actions.ActionHandler
import io.github.simllll.panelelevate.dashboard.actions.haActions
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.cards.CardRenderer
import io.github.simllll.panelelevate.dashboard.cards.GridOptions
import io.github.simllll.panelelevate.dashboard.cards.HaCard
import io.github.simllll.panelelevate.dashboard.cards.entity
import io.github.simllll.panelelevate.dashboard.cards.now
import io.github.simllll.panelelevate.dashboard.custom.Css
import io.github.simllll.panelelevate.dashboard.custom.JsNull
import io.github.simllll.panelelevate.dashboard.custom.jsString
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.hass.isActive
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.json.asArray
import io.github.simllll.panelelevate.dashboard.json.asInt
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.asString
import io.github.simllll.panelelevate.dashboard.json.bool
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.obj
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.theme.CssColors
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `custom:button-card` (custom-cards/button-card v4+): icon / name / state / label with
 * `show_*`, JS templates (`[[[ … ]]]`, see [ButtonCardJs]) in name, label, icon, color, state
 * values and styles, HTML in name/label ([ButtonCardHtml]), `state:` overrides (operators `==`,
 * `!=`, `<`, `<=`, `>`, `>=`, `regex`, `template`, `default`), `styles` (card, name, state, label,
 * icon), `color` / `color_type` (icon, card, label-card, blank-card), `size`, `section_mode` and
 * tap / hold / double-tap actions with button-card's defaults (`entity` in service data is replaced
 * by the card's entity).
 *
 * Not supported: lock, spinner, tooltips, custom_fields, entity pictures / live streams,
 * `button_card_templates`, non-vertical layouts are approximated as "icon left, texts right".
 */
class CustomButtonCard(private val raw: JsonObject) : CardRenderer {
    private val entityId: String? = raw.string("entity")
    private val showName = raw.bool("show_name") ?: true
    private val showIcon = raw.bool("show_icon") ?: true
    private val showState = raw.bool("show_state") ?: false
    private val showLabel = raw.bool("show_label") ?: false
    private val showUnits = raw.bool("show_units") ?: true
    private val showLastChanged = raw.bool("show_last_changed") ?: false
    private val layoutName = raw.string("layout") ?: "vertical"
    private val colorType = raw.string("color_type") ?: "icon"
    private val sectionMode = raw.bool("section_mode") == true
    private val stateConfigs: List<JsonObject> = raw["state"].asArray()?.mapNotNull { it.asObject() } ?: emptyList()

    /** Config with button-card's action defaults (setConfig + `_finishSetup`). */
    private val config: JsonObject = JsonObject(
        jsonOf(
            "tap_action" to jsonOf("action" to defaultTapAction()),
            "hold_action" to jsonOf("action" to "none"),
            "double_tap_action" to jsonOf("action" to "none"),
        ).let { defaults ->
            if (entityId != null && entityId.substringBefore('.') == "input_button" && !raw.containsKey("tap_action")) {
                defaults + ("tap_action" to jsonOf("action" to "perform-action", "perform_action" to "input_button.press", "target" to mapOf("entity_id" to entityId)))
            } else defaults
        } + raw
    )

    /** Entities referenced by templates (`states['x']`), watched so templates update. */
    private val templateEntities: List<String> = ButtonCardJs.referencedEntities(raw.toString()).filter { it != entityId }

    private fun defaultTapAction(): String {
        val domain = entityId?.substringBefore('.') ?: return "none"
        return if (domain in TOGGLE_DOMAINS_BUTTON_CARD) "toggle" else "more-info"
    }

    override fun gridOptions(): GridOptions =
        if (sectionMode) GridOptions(columns = 6, rows = 2, minColumns = 1, minRows = 1) else GridOptions()

    @Composable
    override fun isVisible(ctx: CardContext): Boolean {
        val hidden = raw["hidden"] ?: return true
        val entity = ctx.entity(entityId)
        return !(resolve(ctx, entity, hidden)?.let { it == "true" } ?: false)
    }

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(entityId)
        // Recompose when entities used by templates change.
        templateEntities.forEach { id -> key(id) { ctx.entity(id) } }
        val c = HaTheme.colors
        val stateConfig = remember(entity, stateConfigs) { matchingState(ctx, entity) }
        val styles = mergedStyles(ctx, entity, stateConfig)
        val cardStyle = styles["card"].orEmpty()

        val colorValue = resolve(ctx, entity, stateConfig?.get("color") ?: raw["color"])
        val color = buttonColor(entity, colorValue)
        val cardBackground: Color
        val textColor: Color
        when (colorType) {
            "card", "label-card", "blank-card" -> {
                cardBackground = color
                textColor = if (color.luminance() > 0.35f) Color(0xFF1C1C1C) else Color(0xFFE1E1E1)
            }
            else -> {
                cardBackground = Css.color(cardStyle["background-color"] ?: cardStyle["background"]) ?: c.cardBackground
                textColor = Css.color(cardStyle["color"]) ?: c.primaryText
            }
        }
        val iconColor = if (colorType == "icon") color else textColor

        val name: String? = if (showName) {
            resolve(ctx, entity, stateConfig?.get("name") ?: raw["name"]) ?: entity?.friendlyName
        } else null
        val stateText: String? = if (showState && entity != null) stateDisplay(ctx, entity) else null
        val label: String? = when {
            showLabel && showLastChanged && entity != null -> ctx.formatter.relativeTime(entity.lastChanged, ctx.now(), capitalize = true)
            showLabel -> resolve(ctx, entity, stateConfig?.get("label") ?: raw["label"])
            else -> null
        }
        val iconName: String? = if (showIcon) {
            val override = resolve(ctx, entity, stateConfig?.get("icon") ?: raw["icon"])
            if (override != null || entity != null) ctx.icons.icon(entity, override) else null
        } else null

        val baseFont = Css.length(cardStyle["font-size"], 0f) ?: (1.2f * Css.REM_PX)
        val baseWeight = Css.fontWeight(cardStyle["font-weight"]) ?: FontWeight.Normal
        val fixedHeight = Css.length(cardStyle["height"], 0f)

        val cardModifier = modifier
            .then(if (fixedHeight != null) Modifier.height(fixedHeight.dp) else Modifier)
        val interactive = ActionHandler.isInteractive(config)
        HaCard(cardModifier, background = cardBackground) {
            if (colorType == "blank-card") return@HaCard
            val parts = ArrayList<@Composable () -> Unit>()
            val textSlots = ArrayList<TextSlot>()
            val iconSlot = iconName?.let { IconSlot(it, styles["icon"].orEmpty()) }
            listOfNotNull(
                name?.let { TextSlot(it, styles["name"].orEmpty()) },
                stateText?.let { TextSlot(it, styles["state"].orEmpty()) },
                label?.let { TextSlot(it, styles["label"].orEmpty()) },
            ).forEach { textSlots += it }
            if (iconSlot != null) parts += { MdiIcon(iconSlot.icon, Css.color(iconSlot.style["color"]) ?: iconColor, size = 0.dp) }
            textSlots.forEach { slot -> parts += { SlotText(slot, baseFont, baseWeight, textColor) } }
            ButtonLayout(
                hasIcon = iconSlot != null,
                iconStyle = iconSlot?.style.orEmpty(),
                textStyles = textSlots.map { it.style },
                cardStyle = cardStyle,
                vertical = layoutName == "vertical",
                size = raw.string("size") ?: "40%",
                modifier = Modifier.haActions(ctx, config, entityIdSubstitution = entityId, pressColor = iconColor, enabled = interactive),
                content = { parts.forEach { it() } },
            )
        }
    }

    @Immutable
    private class TextSlot(val text: String, val style: Map<String, String>)

    @Immutable
    private class IconSlot(val icon: String, val style: Map<String, String>)

    /** Text of name/state/label: plain or HTML (inline icons), single line with ellipsis. */
    @Composable
    private fun SlotText(slot: TextSlot, baseFontPx: Float, baseWeight: FontWeight, defaultColor: Color) {
        val style = slot.style
        val fontPx = Css.length(style["font-size"], 0f, baseFontPx) ?: baseFontPx
        val color = Css.color(style["color"]) ?: defaultColor
        val weight = Css.fontWeight(style["font-weight"]) ?: baseWeight
        val density = LocalDensity.current
        val fontSize = with(density) { fontPx.dp.toSp() }
        val textStyle = TextStyle(
            fontFamily = HaTheme.typography.fontFamily,
            fontSize = fontSize,
            fontWeight = weight,
            lineHeight = (fontSize.value * NORMAL_LINE_HEIGHT).sp,
            color = color,
            textAlign = when (style["justify-self"] ?: style["text-align"]) {
                "start", "left" -> TextAlign.Start
                "end", "right" -> TextAlign.End
                else -> TextAlign.Center
            },
        )
        val text = when (style["text-transform"]) {
            "uppercase" -> slot.text.uppercase()
            "lowercase" -> slot.text.lowercase()
            else -> slot.text
        }
        if (!ButtonCardHtml.isHtml(text)) {
            BasicText(text, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            return
        }
        val pieces = remember(text) { ButtonCardHtml.parse(text) }
        val inline = HashMap<String, InlineTextContent>()
        val annotated: AnnotatedString = buildAnnotatedString {
            pieces.forEachIndexed { i, p ->
                when (p) {
                    is HtmlPiece.Text -> withStyle(
                        SpanStyle(
                            color = Css.color(p.color) ?: Color.Unspecified,
                            fontWeight = if (p.bold) FontWeight.Bold else null,
                            fontStyle = if (p.italic) FontStyle.Italic else null,
                            fontSize = Css.length(p.fontSize, 0f, fontPx)?.let { with(density) { it.dp.toSp() } } ?: TextUnit.Unspecified,
                        )
                    ) { append(p.text) }
                    HtmlPiece.LineBreak -> append('\n')
                    is HtmlPiece.Icon -> {
                        val id = "icon$i"
                        val w = p.width ?: p.height ?: DEFAULT_ICON_PX
                        val h = p.height ?: p.width ?: DEFAULT_ICON_PX
                        val iconTint = Css.color(p.color) ?: color
                        inline[id] = InlineTextContent(
                            Placeholder(with(density) { w.dp.toSp() }, with(density) { h.dp.toSp() }, PlaceholderVerticalAlign.TextCenter)
                        ) {
                            Box(Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
                                MdiIcon(p.icon, iconTint, size = min(w, h).dp)
                            }
                        }
                        appendInlineContent(id, " ")
                    }
                }
            }
        }
        val lines = pieces.count { it == HtmlPiece.LineBreak } + 1
        BasicText(annotated, style = textStyle, maxLines = lines, overflow = TextOverflow.Ellipsis, inlineContent = inline)
    }

    /** Resolves a config value: JS template, or the plain value as text. */
    private fun resolve(ctx: CardContext, entity: EntityState?, value: JsonElement?): String? {
        value ?: return null
        val text = value.asString() ?: return value.toString()
        val code = ButtonCardJs.templateCode(text) ?: return text
        return try {
            ButtonCardJs.evaluate(code, scope(ctx, entity)).let { v ->
                if (v == null || v == JsNull) null else jsString(v)
            }
        } catch (e: JsUnsupported) {
            Log.w(TAG, "unsupported template: ${e.message}")
            null
        }
    }

    private fun scope(ctx: CardContext, entity: EntityState?) = JsScope(
        entity = entity,
        states = { id -> ctx.hass.store[id] },
        variables = raw.obj("variables") ?: JsonObject(emptyMap()),
    )

    /** `styles` of the config merged with those of the matching `state` entry, templates resolved. */
    private fun mergedStyles(ctx: CardContext, entity: EntityState?, stateConfig: JsonObject?): Map<String, Map<String, String>> {
        val result = HashMap<String, MutableMap<String, String>>()
        fun add(styles: JsonObject?) {
            styles ?: return
            for ((area, list) in styles) {
                val target = result.getOrPut(area) { LinkedHashMap() }
                val entries = list.asArray()?.mapNotNull { it.asObject() } ?: listOfNotNull(list.asObject())
                for (o in entries) for ((prop, v) in o) {
                    resolve(ctx, entity, v)?.let { target[prop.lowercase()] = it }
                }
            }
        }
        add(raw.obj("styles"))
        add(stateConfig?.obj("styles"))
        return result
    }

    /** `_getMatchingConfigState`: first matching `state` entry, else the `default` one. */
    private fun matchingState(ctx: CardContext, entity: EntityState?): JsonObject? {
        if (stateConfigs.isEmpty()) return null
        val state = entity?.state
        var default: JsonObject? = null
        for (s in stateConfigs) {
            val op = s.string("operator") ?: "=="
            if (op == "default") {
                if (default == null) default = s
                continue
            }
            val value = s["value"]
            val matched = when (op) {
                "==" -> state != null && value.asString() == state
                "!=" -> value.asString() != state
                "<", "<=", ">", ">=" -> {
                    val a = state?.toDoubleOrNull()
                    val b = value.asString()?.toDoubleOrNull()
                    a != null && b != null && when (op) { "<" -> a < b; "<=" -> a <= b; ">" -> a > b; else -> a >= b }
                }
                "regex" -> state != null && value.asString()?.let { runCatching { Regex(it).containsMatchIn(state) }.getOrDefault(false) } == true
                "template" -> resolve(ctx, entity, value) == "true"
                else -> false
            }
            if (matched) return s
        }
        return default
    }

    /** Icon color (`--button-card-color`): explicit color, light color, state color or text color. */
    @Composable
    private fun buttonColor(entity: EntityState?, colorValue: String?): Color {
        val c = HaTheme.colors
        val auto = colorValue == "auto" || colorValue == "auto-no-temperature"
        if (colorValue != null && !auto) {
            val explicit = Css.color(colorValue) ?: c.primaryText
            return if (entity == null || entity.isActive()) explicit else c.stateInactive
        }
        if (entity == null) return c.primaryText
        if (entity.domain == "light") {
            val rgb = entity.attr("rgb_color").asArray()?.mapNotNull { it.asInt() }
            if (rgb != null && rgb.size == 3 && entity.isActive()) return CssColors.fromRgb(rgb[0], rgb[1], rgb[2])
        }
        return if (entity.isActive()) CssColors.stateColor(entity) ?: c.stateActive else c.stateInactive
    }

    @Composable
    private fun stateDisplay(ctx: CardContext, entity: EntityState): String {
        val units = raw.string("units")
        if (units != null) return if (showUnits) "${entity.state} $units" else entity.state
        val formatted = ctx.formatter.formatState(entity, ctx.now())
        return if (showUnits || entity.unit == null) formatted else formatted.removeSuffix(ctx.formatter.unitSuffix(entity.unit))
    }

    private companion object {
        const val TAG = "ButtonCard"
        val TOGGLE_DOMAINS_BUTTON_CARD = setOf("fan", "input_boolean", "light", "switch", "group", "automation", "humidifier")
        const val DEFAULT_ICON_PX = 24f

        /** CSS `line-height: normal` of Roboto. */
        const val NORMAL_LINE_HEIGHT = 1.172f
    }
}

/**
 * The `#container` grid of button-card's vertical layout: padding `4% 0` (of the width), the icon
 * row takes the free height (icon `size`, default 40 % of the width), name/state/label below at
 * their content height, the whole block centered vertically. Non-vertical layouts put the icon
 * left of the texts.
 */
@Composable
private fun ButtonLayout(
    hasIcon: Boolean,
    iconStyle: Map<String, String>,
    textStyles: List<Map<String, String>>,
    cardStyle: Map<String, String>,
    vertical: Boolean,
    size: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val widthDp = width / density
        val pad = padding(cardStyle["padding"], widthDp).map { (it * density).roundToInt() }
        val (padTop, padRight, padBottom, padLeft) = pad
        val innerWidth = (width - padLeft - padRight).coerceAtLeast(0)
        var index = 0
        val iconMeasurable = if (hasIcon) measurables[index++] else null
        val textMeasurables = measurables.drop(index)
        val iconWidthStyle = Css.length(iconStyle["width"], widthDp)?.times(density)
        var iconSize = (iconWidthStyle ?: (Css.length(size, innerWidth / density)?.times(density) ?: innerWidth * 0.4f)).roundToInt()

        if (!vertical && iconMeasurable != null) {
            val texts = textMeasurables.map { it.measure(Constraints(maxWidth = (innerWidth - iconSize).coerceAtLeast(0))) }
            val textHeight = texts.sumOf { it.height }
            if (constraints.hasBoundedHeight) iconSize = min(iconSize, constraints.maxHeight - padTop - padBottom).coerceAtLeast(0)
            val icon = iconMeasurable.measure(Constraints.fixed(iconSize, iconSize))
            val contentHeight = max(icon.height, textHeight)
            val height = if (constraints.hasBoundedHeight) max(constraints.maxHeight, constraints.minHeight)
            else max(contentHeight + padTop + padBottom, constraints.minHeight)
            return@Layout layout(width, height) {
                icon.place(padLeft, (height - icon.height) / 2)
                var y = (height - textHeight) / 2
                texts.forEach {
                    it.place(padLeft + icon.width, y)
                    y += it.height
                }
            }
        }

        val texts = textMeasurables.map { it.measure(Constraints(maxWidth = innerWidth)) }
        val textHeight = texts.sumOf { it.height }
        if (constraints.hasBoundedHeight) {
            val available = constraints.maxHeight - padTop - padBottom - textHeight
            iconSize = min(iconSize, available).coerceAtLeast(0)
        }
        val icon = iconMeasurable?.measure(Constraints.fixed(iconSize, iconSize))
        val contentHeight = (icon?.height ?: 0) + textHeight
        val height = if (constraints.hasBoundedHeight) max(constraints.maxHeight, constraints.minHeight)
        else max(contentHeight + padTop + padBottom, constraints.minHeight)
        layout(width, height) {
            var y = padTop + (height - padTop - padBottom - contentHeight) / 2
            icon?.let {
                it.place(padLeft + (innerWidth - it.width) / 2, y)
                y += it.height
            }
            texts.forEachIndexed { i, it ->
                val x = when (textStyles.getOrNull(i)?.get("justify-self")) {
                    "start", "left" -> padLeft
                    "end", "right" -> padLeft + innerWidth - it.width
                    else -> padLeft + (innerWidth - it.width) / 2
                }
                it.place(x, y)
                y += it.height
            }
        }
    }
}

/** CSS `padding` shorthand in dp (top, right, bottom, left); default button-card `4% 0`. */
private fun padding(value: String?, widthDp: Float): List<Float> {
    val parts = (value ?: "4% 0px").trim().split(Regex("\\s+")).map { Css.length(it, widthDp) ?: 0f }
    return when (parts.size) {
        1 -> listOf(parts[0], parts[0], parts[0], parts[0])
        2 -> listOf(parts[0], parts[1], parts[0], parts[1])
        3 -> listOf(parts[0], parts[1], parts[2], parts[1])
        else -> listOf(parts[0], parts[1], parts[2], parts[3])
    }
}
