package com.stretter.shellyelevateservice.dashboard.cards.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.cards.CardRenderer
import com.stretter.shellyelevateservice.dashboard.cards.PlaceholderCard
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.serialization.json.JsonObject

/**
 * `custom:bubble-card` (Bubble Card v3.2): dispatches on `card_type`. Implemented: `pop-up`
 * ([BubblePopUpCard]), `button` ([BubbleButtonCard]) and `media-player` ([BubbleMediaPlayerCard]).
 * Other types render the placeholder.
 */
object BubbleCard {
    fun create(config: JsonObject): CardRenderer = when (config.string("card_type")) {
        "pop-up" -> BubblePopUpCard(config)
        "button" -> BubbleButtonCard(config)
        "media-player" -> BubbleMediaPlayerCard(config)
        else -> PlaceholderCard("custom:bubble-card (${config.string("card_type")})")
    }
}

/** Bubble Card colors in the default dark theme (`--bubble-*` fallbacks resolved). */
internal object BubbleColors {
    /** `--bubble-main-background-color` → `--secondary-background-color`. */
    val main: Color @Composable get() = HaTheme.colors.secondaryBackground

    /** `--bubble-icon-background-color` → `--card-background-color`. */
    val iconBackground: Color @Composable get() = HaTheme.colors.cardBackground

    /** `--bubble-pop-up-background-color` as rendered on the wall display. */
    val popUp: Color @Composable get() = HaTheme.colors.cardBackground

    /** `--bubble-default-color`: 70 % of rgb(0,145,255) over the card background. */
    val accent = Color(0xFF086EBB)

    /** `.bubble-pop-up` backdrop dims the dashboard by ~25 %. */
    val backdrop = Color(0x40000000)
}

/** Bubble's round icon container (`.bubble-icon-container`). */
@Composable
internal fun BubbleIconContainer(
    size: Dp,
    modifier: Modifier = Modifier,
    background: Color = BubbleColors.iconBackground,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Height of a Bubble row: 56px (`large`, the default in sections views) or 50px (`normal`). */
internal fun bubbleHeight(config: JsonObject): Dp = if (config.string("card_layout") == "normal") 50.dp else 56.dp
