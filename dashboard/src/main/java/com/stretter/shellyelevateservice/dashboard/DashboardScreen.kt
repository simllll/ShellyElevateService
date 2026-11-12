package com.stretter.shellyelevateservice.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.browsermod.browserModReporting
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.hass.ConnectionState
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.json.objects
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.lock.GuestLockLayer
import com.stretter.shellyelevateservice.dashboard.lovelace.MasonryView
import com.stretter.shellyelevateservice.dashboard.lovelace.SectionsView
import com.stretter.shellyelevateservice.dashboard.lovelace.SwipeNavConfig
import com.stretter.shellyelevateservice.dashboard.lovelace.ViewSwitcher
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import com.stretter.shellyelevateservice.dashboard.theme.ProvideHaTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** Root composable: current view, popup layer and the connection indicator. */
@Composable
fun DashboardScreen(runtime: DashboardRuntime) {
    ProvideHaTheme {
        val lovelace by runtime.lovelace.collectAsState()
        val connection by runtime.hass.connectionState.collectAsState()
        Box(
            Modifier
                .fillMaxSize()
                .background(HaTheme.colors.primaryBackground)
                .browserModReporting(runtime.browserMod)
                .pointerInput(runtime.navigator) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) runtime.navigator.onUserInteraction()
                        }
                    }
                }
        ) {
            when (val state = lovelace) {
                is LovelaceState.Loaded -> CurrentView(state.config, runtime)
                LovelaceState.NotConfigured -> Message(
                    "mdi:home-assistant",
                    "Dashboard nicht konfiguriert",
                    "adb shell am broadcast -n com.stretter.shellyelevateservice/.dashboard.DashboardConfigReceiver " +
                        "-a com.stretter.shellyelevateservice.dashboard.CONFIGURE --es url http://<ha>:8123 --es token <token> --es dashboard <url_path>",
                )
                LovelaceState.Loading -> Message("mdi:home-assistant", "Lade Dashboard …", (connection as? ConnectionState.Disconnected)?.reason)
                is LovelaceState.Error -> Message("mdi:alert-outline", "Dashboard konnte nicht geladen werden", state.message)
            }
            runtime.popups.FloatingLayer()
            // Above the view, below every popup: browser_mod popups (alarm keypad) stay usable.
            val lockConfig by runtime.guestLockConfig.collectAsState()
            GuestLockLayer(runtime.guestLock, lockConfig, runtime.cardContext)
            runtime.popups.Layer(runtime.cardContext)
            runtime.buttonFeedback.Layer()
            ConnectionIndicator(connection)
        }
    }
}

/**
 * The current view with swipe navigation (`swipe_nav`) and the return to the first view after
 * [DashboardRuntime.returnHomeAfterS] seconds without a touch.
 */
@Composable
private fun CurrentView(config: JsonObject, runtime: DashboardRuntime) {
    val views = config.objects("views")
    if (views.isEmpty()) return
    val navigator = runtime.navigator
    val swipeNav = remember(config) { SwipeNavConfig.parse(config) }
    val index = SwipeNavConfig.viewIndexOrNull(views, navigator.viewPath) ?: 0
    val returnHomeAfterS by runtime.returnHomeAfterS.collectAsState()
    LaunchedEffect(index, navigator.interactions, returnHomeAfterS) {
        if (returnHomeAfterS <= 0 || index == 0) return@LaunchedEffect
        delay(returnHomeAfterS * 1000L)
        navigator.home()
    }
    ViewSwitcher(views, swipeNav, navigator) { view -> RenderView(view, runtime.cardContext) }
}

/** Renders a view: `sections`, or `masonry` (also a view without `type`). */
@Composable
fun RenderView(view: JsonObject, ctx: CardContext) {
    when (view.string("type")) {
        "sections" -> SectionsView(view, ctx)
        null, "masonry" -> MasonryView(view, ctx)
        else -> Message("mdi:view-dashboard-outline", "Ansichtstyp „${view.string("type")}“ wird nicht unterstützt", "Nativ dargestellt werden Abschnitte- (sections) und Masonry-Ansichten.")
    }
}

@Composable
private fun Message(icon: String, title: String, detail: String?) {
    val t = HaTheme.typography
    val c = HaTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MdiIcon(icon, c.secondaryText, size = 48.dp)
            BasicText(title, style = t.style(t.sizeXl, color = c.primaryText).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(top = 16.dp))
            if (detail != null) {
                BasicText(detail, style = t.style(t.sizeM, color = c.secondaryText).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * HA's "connection lost" toast, shown only after 2 s without connection so short reconnects stay
 * invisible.
 */
@Composable
private fun ConnectionIndicator(state: ConnectionState) {
    var visible by remember { mutableStateOf(false) }
    val problem = state is ConnectionState.Disconnected || state is ConnectionState.Connecting || state is ConnectionState.AuthFailed
    LaunchedEffect(problem) {
        if (problem) {
            delay(2_000)
            visible = true
        } else visible = false
    }
    val t = HaTheme.typography
    val c = HaTheme.colors
    Box(Modifier.fillMaxSize().padding(bottom = 16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible && problem, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .background(c.secondaryBackground, RoundedCornerShape(HaTheme.dimens.radiusSm))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MdiIcon(if (state is ConnectionState.AuthFailed) "mdi:key-alert" else "mdi:lan-disconnect", c.warning, size = 20.dp)
                BasicText(
                    when (state) {
                        is ConnectionState.AuthFailed -> "Anmeldung fehlgeschlagen: ${state.message}"
                        else -> "Verbindung getrennt. Verbinde erneut …"
                    },
                    style = t.style(t.sizeM, color = c.primaryText),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
