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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.hass.ConnectionState
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.json.objects
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.lovelace.SectionsView
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
            runtime.popups.Layer(runtime.cardContext)
            ConnectionIndicator(connection)
        }
    }
}

@Composable
private fun CurrentView(config: JsonObject, runtime: DashboardRuntime) {
    val views = config.objects("views")
    val path = runtime.navigator.viewPath
    val view = views.firstOrNull { path != null && it.string("path") == path }
        ?: path?.toIntOrNull()?.let { views.getOrNull(it) }
        ?: views.firstOrNull()
        ?: return
    RenderView(view, runtime.cardContext)
}

/** Renders a view; only `sections` views are supported natively. */
@Composable
fun RenderView(view: JsonObject, ctx: CardContext) {
    when (view.string("type")) {
        "sections" -> SectionsView(view, ctx)
        else -> Message("mdi:view-dashboard-outline", "Ansichtstyp „${view.string("type") ?: "masonry"}“ wird nicht unterstützt", "Nur Abschnitte-Ansichten (sections) werden nativ dargestellt.")
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
