package io.github.simllll.panelelevate.dashboard.icons

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Material Design Icons (@mdi/font 7.4.47, the version the HA frontend uses), drawn from the bundled
 * webfont (`assets/mdi.ttf`) with the name → codepoint map in `assets/mdi-map.txt`.
 *
 * Call [init] once (DashboardActivity does). Icon names may carry the `mdi:` / `hass:` prefix.
 */
object MdiIcons {
    private const val TAG = "MdiIcons"

    @Volatile private var typeface: Typeface? = null
    @Volatile private var codepoints: Map<String, Int> = emptyMap()

    /** Glyph baseline as a fraction of the icon size (font: 512 units/em, ascent 448, descent 64). */
    private const val BASELINE = 448f / 512f

    fun init(context: Context) {
        if (typeface != null) return
        synchronized(this) {
            if (typeface != null) return
            val assets = context.applicationContext.assets
            codepoints = try {
                BufferedReader(InputStreamReader(assets.open("mdi-map.txt"))).useLines { lines ->
                    val map = HashMap<String, Int>(8192)
                    lines.forEach { line ->
                        if (line.isEmpty() || line[0] == '#') return@forEach
                        val sep = line.indexOf(' ')
                        if (sep > 0) map[line.substring(0, sep)] = line.substring(sep + 1).toInt(16)
                    }
                    map
                }
            } catch (e: Exception) {
                Log.e(TAG, "loading icon map failed", e)
                emptyMap()
            }
            typeface = Typeface.createFromAsset(assets, "mdi.ttf")
        }
    }

    /** Codepoint for `mdi:name`, `name` or `hass:name`; null if unknown. */
    fun codepoint(icon: String?): Int? {
        if (icon.isNullOrBlank()) return null
        val name = icon.trim().substringAfter(':')
        return codepoints[name]
    }

    fun exists(icon: String?): Boolean = codepoint(icon) != null

    internal fun typeface(): Typeface? = typeface

    internal val paint: Paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    }

    internal fun baseline(sizePx: Float) = sizePx * BASELINE
}

/**
 * Draws an MDI icon like `<ha-icon>`: a square of [size] (`--mdc-icon-size`, default 24px) with the
 * glyph tinted [tint]. Unknown icons draw nothing but keep their space.
 */
@Composable
fun MdiIcon(
    icon: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val glyph = remember(icon) { MdiIcons.codepoint(icon)?.let { String(Character.toChars(it)) } }
    Spacer(
        modifier
            .size(size)
            .drawBehind {
                if (glyph == null) return@drawBehind
                val tf = MdiIcons.typeface() ?: return@drawBehind
                val px = this.size.minDimension
                drawIntoCanvas { canvas ->
                    val paint = MdiIcons.paint
                    paint.typeface = tf
                    paint.textSize = px
                    paint.color = tint.toArgb()
                    val top = (this.size.height - px) / 2f
                    canvas.nativeCanvas.drawText(glyph, this.size.width / 2f, top + MdiIcons.baseline(px), paint)
                }
            }
    )
}
