package com.stretter.shellyelevateservice.dashboard.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Home Assistant default theme, dark mode, as of frontend 20261028.5
 * (src/resources/theme: core, typography, semantic and color globals).
 * 1 CSS px == 1 dp. The variable names in the comments are the frontend's CSS custom properties.
 */

/** Raw palette (color/core.globals.ts). */
object HaPalette {
    val Primary05 = Color(0xFF001721)
    val Primary10 = Color(0xFF002E3E)
    val Primary20 = Color(0xFF004156)
    val Primary30 = Color(0xFF006787)
    val Primary40 = Color(0xFF009AC7)
    val Primary50 = Color(0xFF18BCF2)
    val Primary60 = Color(0xFF37C8FD)
    val Primary70 = Color(0xFF7BD4FB)
    val Primary80 = Color(0xFFB9E6FC)
    val Neutral05 = Color(0xFF141414)
    val Neutral10 = Color(0xFF202020)
    val Neutral20 = Color(0xFF363636)
    val Neutral30 = Color(0xFF4A4A4A)
    val Neutral40 = Color(0xFF5E5E5E)
    val Neutral50 = Color(0xFF7A7A7A)
    val Neutral60 = Color(0xFF989898)
    val Neutral70 = Color(0xFFB1B1B1)
    val Neutral80 = Color(0xFFCCCCCC)
    val Neutral90 = Color(0xFFE6E6E6)
}

/** Semantic colors, dark mode (darkColorStyles / darkSemanticColorStyles). */
@Immutable
data class HaColors(
    val primaryBackground: Color = Color(0xFF111111),   // --primary-background-color
    val cardBackground: Color = Color(0xFF1C1C1C),      // --card-background-color
    val secondaryBackground: Color = Color(0xFF282828), // --secondary-background-color
    val primaryText: Color = Color(0xFFE1E1E1),         // --primary-text-color
    val secondaryText: Color = Color(0xFF9B9B9B),       // --secondary-text-color
    val disabledText: Color = Color(0xFF6F6F6F),        // --disabled-text-color
    val textPrimaryColor: Color = Color.White,          // --text-primary-color (text on primary)
    val divider: Color = Color(0x1FE1E1E1),             // --divider-color rgba(225,225,225,.12)
    val outline: Color = Color(0x1FE1E1E1),             // --outline-color
    val outlineHover: Color = Color(0x3DE1E1E1),        // --outline-hover-color
    val shadow: Color = Color(0x7A000000),              // --shadow-color rgba(0,0,0,.48)
    val primary: Color = HaPalette.Primary40,           // --primary-color
    val accent: Color = Color(0xFFFF9800),              // --accent-color
    val stateIcon: Color = Color(0xFF44739E),           // --state-icon-color
    val stateActive: Color = Color(0xFFFFC107),         // --state-active-color (amber)
    val stateInactive: Color = Color(0xFF9E9E9E),       // --state-inactive-color (grey)
    val stateUnavailable: Color = Color(0xFF6F6F6F),    // --state-unavailable-color (= disabled text)
    val disabled: Color = Color(0xFF464646),            // --disabled-color
    val error: Color = Color(0xFFDB4437),
    val warning: Color = Color(0xFFFFA600),
    val success: Color = Color(0xFF43A047),
    val info: Color = Color(0xFF039BE5),
    val codeBackground: Color = Color(0xFF111111),      // --markdown-code-background-color
    val scrim: Color = Color(0x52000000),               // dialog backdrop brightness(68%)
    // ha-switch
    val switchTrack: Color = HaPalette.Neutral10,       // --ha-color-fill-disabled-quiet-resting
    val switchBorder: Color = HaPalette.Neutral50,      // --ha-color-border-neutral-normal
    val switchThumb: Color = HaPalette.Neutral60,       // --ha-color-on-neutral-normal
    val switchCheckedTrack: Color = HaPalette.Primary10, // --ha-color-fill-primary-normal-resting
    val switchCheckedBorder: Color = HaPalette.Primary40, // --ha-color-border-primary-loud
    val switchCheckedThumb: Color = HaPalette.Primary60, // --ha-color-on-primary-normal
)

/** Typography (typography.globals.ts). Roboto is the Android system sans-serif. */
@Immutable
data class HaTypography(
    val fontFamily: FontFamily = FontFamily.SansSerif,
    val sizeXs: TextUnit = 10.sp,
    val sizeS: TextUnit = 12.sp,
    val sizeM: TextUnit = 14.sp,
    val sizeL: TextUnit = 16.sp,
    val sizeXl: TextUnit = 20.sp,
    val size2xl: TextUnit = 24.sp,
    val size3xl: TextUnit = 28.sp,
    val size4xl: TextUnit = 32.sp,
    val lineHeightCondensed: Float = 1.2f,
    val lineHeightNormal: Float = 1.6f,
    val lineHeightExpanded: Float = 2f,
) {
    fun style(
        size: TextUnit = sizeM,
        weight: FontWeight = FontWeight.Normal,
        lineHeight: Float = lineHeightNormal,
        letterSpacing: TextUnit = TextUnit.Unspecified,
        color: Color = Color.Unspecified,
    ) = TextStyle(
        fontFamily = fontFamily,
        fontSize = size,
        fontWeight = weight,
        lineHeight = (size.value * lineHeight).sp,
        letterSpacing = letterSpacing,
        color = color,
    )

    /** Body text: 14px, line-height normal. */
    val body: TextStyle get() = style()

    /** `.card-header`: 24px, weight 400, line-height condensed, letter-spacing -0.012em. */
    val cardHeader: TextStyle get() = style(size2xl, FontWeight.Normal, lineHeightCondensed, (-0.012).em)
}

/** Spacing, radii and sizes (core.globals.ts plus component constants). */
@Immutable
data class HaDimens(
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    val space4: Dp = 16.dp,
    val space5: Dp = 20.dp,
    val space6: Dp = 24.dp,
    val radiusSm: Dp = 4.dp,
    val radiusMd: Dp = 8.dp,
    val radiusLg: Dp = 12.dp,       // --ha-card-border-radius default
    val radiusXl: Dp = 16.dp,       // sections
    val radius3xl: Dp = 24.dp,      // dialogs
    val cardBorderWidth: Dp = 1.dp, // --ha-card-border-width
    // sections view (hui-sections-view)
    val viewRowGap: Dp = 24.dp,
    val viewColumnGap: Dp = 32.dp,
    val viewColumnMinWidth: Dp = 320.dp,
    val viewColumnMaxWidth: Dp = 500.dp,
    val viewNarrowColumnGap: Dp = 8.dp,
    // grid section (hui-grid-section)
    val gridRowHeight: Dp = 56.dp,
    val gridRowGap: Dp = 8.dp,
    val gridColumnGap: Dp = 8.dp,
    // stacks (hui-stack-card, hui-grid-card)
    val stackGap: Dp = 8.dp,
)

@Immutable
data class HaTheme(
    val colors: HaColors = HaColors(),
    val typography: HaTypography = HaTypography(),
    val dimens: HaDimens = HaDimens(),
) {
    companion object {
        val colors: HaColors @Composable @ReadOnlyComposable get() = LocalHaTheme.current.colors
        val typography: HaTypography @Composable @ReadOnlyComposable get() = LocalHaTheme.current.typography
        val dimens: HaDimens @Composable @ReadOnlyComposable get() = LocalHaTheme.current.dimens
    }
}

val LocalHaTheme = staticCompositionLocalOf { HaTheme() }

@Composable
fun ProvideHaTheme(theme: HaTheme = HaTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHaTheme provides theme, content = content)
}
