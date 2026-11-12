package com.stretter.shellyelevateservice.dashboard.custom.timerbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.actions.ActionHandler
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.cards.CardRenderer
import com.stretter.shellyelevateservice.dashboard.cards.PlaceholderCard
import com.stretter.shellyelevateservice.dashboard.cards.StateBadge
import com.stretter.shellyelevateservice.dashboard.cards.entity
import com.stretter.shellyelevateservice.dashboard.cards.now
import com.stretter.shellyelevateservice.dashboard.custom.Css
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.rows.RowRenderer
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * `custom:timer-bar-card` with a single `entity`, i.e. `timer-bar-entity-row`: state badge, name,
 * then a progress bar and the remaining time while active (`bar_width`, `bar_height`,
 * `text_width`, `bar_background`, `bar_foreground`, `bar_radius`, `invert`, `bar_direction`,
 * `format`, `resolution`, `modifications`, `active_icon`), or the translated state when idle.
 * Used as an entities-card row (auto-entities) and as a chrome-less card.
 */
class TimerBarRow(rawConfig: JsonObject) : RowRenderer {
    /** Config with string actions (`tap_action: none`) normalized to action objects. */
    private val config: JsonObject = JsonObject(
        rawConfig + listOf("tap_action", "hold_action", "double_tap_action")
            .mapNotNull { k -> rawConfig[k]?.let { k to (TimerBarLogic.normalizeAction(it) ?: it) } }
    )
    private val watched: List<String> = buildList {
        config.string("entity")?.let(::add)
        listOf("duration", "remain_time", "start_time", "end_time").forEach { k -> config.obj(k)?.string("entity")?.let(::add) }
    }.distinct()

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val states = watched.map { id -> key(id) { ctx.entity(id) } }
        val byId = remember(states) { states.filterNotNull().associateBy { it.entityId } }
        val logic = remember(byId) { TimerBarLogic(config, byId::get) }
        val clock = ctx.now()
        var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val nowMs = maxOf(clock, tick)
        val mode = logic.mode(nowMs)
        val modConfig = logic.modConfig(nowMs)
        if (mode == TimerMode.ACTIVE) {
            val step = if (modConfig.string("resolution") == "seconds" && modConfig.string("format") == null) 1_000L else 5_000L
            LaunchedEffect(step) {
                while (true) {
                    delay(step - System.currentTimeMillis() % step)
                    tick = System.currentTimeMillis()
                }
            }
        }
        val entity = logic.entityId?.let { byId[it] }
        TimerRowLayout(ctx, entity, logic, modConfig, mode, nowMs, modifier)
    }

    @Composable
    private fun TimerRowLayout(
        ctx: CardContext,
        entity: EntityState?,
        logic: TimerBarLogic,
        mod: JsonObject,
        mode: TimerMode,
        nowMs: Long,
        modifier: Modifier,
    ) {
        val t = HaTheme.typography
        val c = HaTheme.colors
        val emPx = with(LocalDensity.current) { t.sizeM.toDp().value }
        val name = config.string("name") ?: entity?.friendlyName ?: config.string("entity").orEmpty()
        val icon = if (mode == TimerMode.ACTIVE || mode == TimerMode.PAUSE) mod.string("active_icon") ?: mod.string("icon") else mod.string("icon")
        val interactive = ActionHandler.isInteractive(config)
        val actions = Modifier.haActions(ctx, config, enabled = interactive)
        val stateText = entity?.let { stateLabel(ctx, it, nowMs) }.orEmpty()
        val remaining = logic.remaining(nowMs) ?: 0.0
        val timeText = TimerBarLogic.formatTime(
            remaining,
            mod.string("format") ?: TimerBarLogic.formatForResolution(remaining, mod.string("resolution")),
        )
        val barWidthCss = when {
            mod.string("layout") == "full_row" || mod["full_row"].asString() == "true" -> "calc(100% - ${mod.string("text_width")})"
            mod.string("layout") == "hide_name" -> null
            else -> mod.string("bar_width")
        }
        val textWidthCss = mod.string("text_width")
        val hideName = mod.string("layout") == "hide_name"

        Layout(
            modifier = modifier.fillMaxWidth().then(actions),
            content = {
                StateBadge(ctx, entity, icon = icon, image = config.string("image"), color = stateColorMode(config))
                BasicText(if (hideName) "" else name, style = t.style(t.sizeM, color = c.primaryText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (mode) {
                    TimerMode.ACTIVE -> {
                        Bar(mod, logic.progress(nowMs) ?: 0.0)
                        TimeText(timeText)
                    }
                    TimerMode.PAUSE -> {
                        BasicText(stateText, style = t.style(t.sizeM, lineHeight = 1.5f, color = c.secondaryText), maxLines = 1)
                        TimeText(timeText)
                    }
                    TimerMode.WAITING -> {
                        val start = logic.startTimeMs()?.let { " " + ctx.formatter.formatTime(it) }.orEmpty()
                        BasicText(stateText + start, style = t.style(t.sizeM, lineHeight = 1.5f, color = c.secondaryText), maxLines = 1)
                    }
                    TimerMode.IDLE -> TimeText(stateText)
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val widthDp = width / density
            fun dpToPx(v: Float) = (v * density).roundToInt()
            val textWidth = Css.length(textWidthCss, widthDp, emPx)?.let(::dpToPx)
            val barWidth = Css.length(barWidthCss, widthDp, emPx)?.let(::dpToPx)
            val badge = measurables[0].measure(Constraints())
            val infoMargin = 16.dp.roundToPx() + 8.dp.roundToPx()
            // Trailing parts with fixed widths (bar, status, time); the idle state text is auto-sized.
            val trailing = measurables.drop(2)
            val fixedWidths: List<Int?> = when (mode) {
                TimerMode.ACTIVE, TimerMode.PAUSE -> listOf(barWidth, textWidth)
                TimerMode.WAITING -> listOf(if (barWidth != null && textWidth != null) barWidth + textWidth else barWidth)
                TimerMode.IDLE -> listOf(null)
            }
            val maxTrailing = (width - badge.width - infoMargin).coerceAtLeast(0)
            val trailingPlaceables = trailing.mapIndexed { i, m ->
                val w = fixedWidths.getOrNull(i)?.coerceIn(0, maxTrailing)
                if (w != null) m.measure(Constraints.fixedWidth(w)) else m.measure(Constraints(maxWidth = maxTrailing))
            }
            val trailingWidth = trailingPlaceables.sumOf { it.width }
            val infoWidth = (width - badge.width - infoMargin - trailingWidth).coerceAtLeast(0)
            val info = measurables[1].measure(Constraints(maxWidth = infoWidth))
            val height = maxOf(40.dp.roundToPx(), badge.height, info.height, trailingPlaceables.maxOfOrNull { it.height } ?: 0)
            layout(width, height) {
                badge.place(0, (height - badge.height) / 2)
                info.place(badge.width + 16.dp.roundToPx(), (height - info.height) / 2)
                var x = width - trailingWidth
                trailingPlaceables.forEach {
                    it.place(x, (height - it.height) / 2)
                    x += it.width
                }
            }
        }
    }

    /** `.bar-container` (min-height 1.5em) with `.bar` (margin-top 2px) and the filled part. */
    @Composable
    private fun Bar(mod: JsonObject, progress: Double) {
        val c = HaTheme.colors
        var p = progress.coerceIn(0.0, 100.0)
        if (mod["invert"].asString() == "true") p = 100 - p
        val height = Css.length(mod.string("bar_height"), 0f) ?: 8f
        val radius = Css.length(mod.string("bar_radius"), 0f) ?: 0f
        val background = Css.color(mod.string("bar_background")) ?: c.secondaryBackground
        val foreground = Css.color(mod.string("bar_foreground")) ?: c.primary
        val shape = RoundedCornerShape(radius.dp)
        val rtl = mod.string("bar_direction") == "rtl"
        Box(Modifier.height(21.dp), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .fillMaxWidth()
                    .height(height.dp)
                    .clip(shape)
                    .background(background, shape),
                contentAlignment = if (rtl) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((p / 100).toFloat()).clip(shape).background(foreground, shape))
            }
        }
    }

    @Composable
    private fun TimeText(text: String) {
        val t = HaTheme.typography
        BasicText(
            text,
            style = t.style(t.sizeM, color = HaTheme.colors.primaryText).copy(textAlign = TextAlign.End),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }

    /** The state text of the idle/pause view (`_e`): translated, with `translations` overrides. */
    private fun stateLabel(ctx: CardContext, entity: EntityState, nowMs: Long): String {
        val fixed = config.obj("state")?.string("fixed")?.replace(Regex("^pause$"), "paused")
        val state = fixed ?: entity.state
        config.obj("translations")?.string(state)?.let { return it }
        return ctx.formatter.formatState(if (fixed != null) entity.copy(state = fixed) else entity, nowMs)
    }

    /** `state_color` of the row config → state-badge color mode. */
    private fun stateColorMode(config: JsonObject): String? = when (config["state_color"]?.asString()) {
        "true" -> "state"
        "false" -> "none"
        else -> null
    }
}

/** `custom:timer-bar-card` as a card: a single-entity bar renders the bare row (no ha-card). */
class TimerBarCard(private val config: JsonObject) : CardRenderer {
    private val row = TimerBarRow(config)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        if (config.containsKey("entities") || config.string("entity") == null) {
            PlaceholderCard("custom:timer-bar-card (entities)").Content(ctx, modifier)
            return
        }
        row.Content(ctx, modifier)
    }
}
