package com.stretter.shellyelevateservice.dashboard.browsermod

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable

/**
 * For the dashboard root: reports touches as browser_mod activity (observed in the initial pass,
 * never consumed) and the root size in CSS px as the `width`/`height` sensors.
 */
@Composable
fun Modifier.browserModReporting(browserMod: BrowserMod): Modifier {
    val density = LocalDensity.current.density
    return this
        .onSizeChanged { browserMod.setSize((it.width / density).toInt(), (it.height / density).toInt()) }
        .pointerInput(browserMod) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) browserMod.reportActivity()
                }
            }
        }
}
