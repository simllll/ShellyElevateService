package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme

/**
 * `<ha-card>`: card background, 1px divider-colored border, 12px radius.
 * Pass `background = Color.Transparent, border = false` for `text_only`-style cards.
 * [clip] clips the content to the rounded shape; only cards with full-bleed pictures/video/maps
 * need it: a clipped card is an extra clipped layer, which costs GPU time on every scroll frame.
 */
@Composable
fun HaCard(
    modifier: Modifier = Modifier,
    background: Color = HaTheme.colors.cardBackground,
    border: Boolean = true,
    clip: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val d = HaTheme.dimens
    val shape = RoundedCornerShape(d.radiusLg)
    Box(
        modifier
            .then(if (clip) Modifier.clip(shape) else Modifier)
            .background(background, shape)
            .then(if (border) Modifier.border(d.cardBorderWidth, HaTheme.colors.divider, shape) else Modifier),
        content = content,
    )
}

/** `.card-header` of ha-card: 24px, padding 20/16/24 (top/sides/bottom). */
@Composable
fun CardHeader(title: String, modifier: Modifier = Modifier, icon: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val t = HaTheme.typography
    val d = HaTheme.dimens
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = d.space4, end = d.space4, top = d.space5, bottom = d.space6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            // entities card: `.icon { padding: 0 18px 0 8px }`
            MdiIcon(icon, HaTheme.colors.primaryText, Modifier.padding(start = 8.dp, end = 18.dp))
        }
        if (title.isNotEmpty()) {
            BasicText(
                title,
                style = t.cardHeader.copy(color = HaTheme.colors.primaryText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            // An empty header (e.g. only a header toggle slot) has no text line, like the empty flex <h1>.
            Spacer(Modifier.weight(1f))
        }
        trailing?.invoke()
    }
}

/** Neutral card for unsupported types: shows the card type so it is clear what is missing. */
class PlaceholderCard(private val type: String) : CardRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val t = HaTheme.typography
        HaCard(modifier) {
            Column(Modifier.padding(HaTheme.dimens.space4)) {
                BasicText(
                    type,
                    style = t.style(t.sizeM, FontWeight.Medium, color = HaTheme.colors.secondaryText),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** `hui-warning`-style card for invalid configs. */
class WarningCard(private val message: String) : CardRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val t = HaTheme.typography
        val warning = HaTheme.colors.warning
        HaCard(modifier, background = warning.copy(alpha = 0.12f)) {
            Row(Modifier.padding(HaTheme.dimens.space4), verticalAlignment = Alignment.CenterVertically) {
                MdiIcon("mdi:alert-outline", warning)
                BasicText(
                    message,
                    style = t.style(t.sizeM, color = HaTheme.colors.primaryText),
                    modifier = Modifier.padding(start = HaTheme.dimens.space2),
                )
            }
        }
    }
}
