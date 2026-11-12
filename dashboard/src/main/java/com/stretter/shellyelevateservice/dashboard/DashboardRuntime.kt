package com.stretter.shellyelevateservice.dashboard

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import com.stretter.shellyelevateservice.dashboard.actions.ActionHandler
import com.stretter.shellyelevateservice.dashboard.actions.Navigator
import com.stretter.shellyelevateservice.dashboard.browsermod.BrowserMod
import com.stretter.shellyelevateservice.dashboard.browsermod.BrowserModPlayer
import com.stretter.shellyelevateservice.dashboard.cards.CardCatalog
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.cards.CardRegistry
import com.stretter.shellyelevateservice.dashboard.format.StateFormatter
import com.stretter.shellyelevateservice.dashboard.hass.ConnectionState
import com.stretter.shellyelevateservice.dashboard.hass.HassClient
import com.stretter.shellyelevateservice.dashboard.icons.IconResolver
import com.stretter.shellyelevateservice.dashboard.image.ImageLoader
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.objects
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.popup.ButtonFeedback
import com.stretter.shellyelevateservice.dashboard.popup.PopupHost
import com.stretter.shellyelevateservice.dashboard.rows.RowRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Loading state of the dashboard config. */
sealed interface LovelaceState {
    data object NotConfigured : LovelaceState
    data object Loading : LovelaceState
    data class Error(val message: String) : LovelaceState
    data class Loaded(val config: JsonObject) : LovelaceState
}

/**
 * Process-wide dashboard state (the `:dashboard` process only hosts the dashboard): HA connection,
 * dashboard config, registries and the [CardContext]. Survives activity recreation, so the
 * connection and entity states are kept across configuration changes.
 */
class DashboardRuntime private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = DashboardSettings(context)
    val hass = HassClient(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    val formatter = StateFormatter()
    val icons = IconResolver()
    val popups = PopupHost()
    val navigator = Navigator(popups)
    val buttonFeedback = ButtonFeedback(popups)
    val actions = ActionHandler(hass, popups, navigator, scope)
    val player = BrowserModPlayer(context, hass, scope)
    val browserMod = BrowserMod(hass, actions, scope, ::browserPath, player)
    val cards = CardRegistry().also(CardCatalog::registerCards)
    val rows = RowRegistry().also(CardCatalog::registerRows)
    val images = ImageLoader(hass, context.cacheDir)

    /** Wall clock, ticking on every half minute. */
    val clock: StateFlow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(30_000 - now % 30_000)
        }
    }.stateIn(scope, SharingStarted.Eagerly, System.currentTimeMillis())

    val cardContext = CardContext(hass, cards, rows, actions, popups, images, formatter, icons, clock)

    private val _lovelace = MutableStateFlow<LovelaceState>(LovelaceState.NotConfigured)
    val lovelace: StateFlow<LovelaceState> = _lovelace.asStateFlow()

    private var dashboardJob: Job? = null
    private var dashboardPath: String? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applySettings() }

    init {
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        applySettings()
        scope.launch {
            hass.connectionState.collect { state ->
                if (state is ConnectionState.Connected) onConnected()
            }
        }
        scope.launch {
            snapshotFlow { navigator.reloadCounter }.drop(1).collect { loadDashboard(force = true) }
        }
        scope.launch {
            snapshotFlow { navigator.viewPath }.collect { browserMod.onPathChanged() }
        }
    }

    private fun applySettings() {
        val config = settings.hassConfig
        if (config == null) _lovelace.value = LovelaceState.NotConfigured
        hass.configure(config)
        browserMod.setBrowserId(settings.browserId)
        val urlPath = settings.dashboard
        if (dashboardJob != null && urlPath == dashboardPath) return
        val switched = dashboardJob != null
        dashboardPath = urlPath
        dashboardJob?.cancel()
        // Another dashboard on the same connection: no reconnect happens, so load it here.
        if (switched && config != null) {
            _lovelace.value = LovelaceState.Loading
            scope.launch { loadDashboard(force = false) }
        }
        // lovelace_updated: reload when the dashboard is saved. Survives reconnects.
        dashboardJob = scope.launch {
            hass.lovelaceUpdates(urlPath).collectLatest { loadDashboard(force = true) }
        }
    }

    /** Runs after every (re)connect: config may have changed while we were away. */
    private fun onConnected() {
        scope.launch { loadDashboard(force = false) }
        scope.launch { loadFrontendResources() }
    }

    private suspend fun loadDashboard(force: Boolean) {
        if (_lovelace.value !is LovelaceState.Loaded) _lovelace.value = LovelaceState.Loading
        try {
            val config = hass.fetchLovelaceConfig(settings.dashboard, force)
            if ((_lovelace.value as? LovelaceState.Loaded)?.config != config) {
                _lovelace.value = LovelaceState.Loaded(config)
                browserMod.onPathChanged()
            }
        } catch (e: Exception) {
            Log.w(TAG, "loading dashboard failed", e)
            if (_lovelace.value !is LovelaceState.Loaded) _lovelace.value = LovelaceState.Error(e.message ?: e.toString())
        }
    }

    /** `window.location.pathname` of the web dashboard for browser_mod's `path` sensor. */
    private fun browserPath(): String {
        val views = (_lovelace.value as? LovelaceState.Loaded)?.config?.objects("views").orEmpty()
        val view = navigator.viewPath ?: views.firstOrNull()?.string("path") ?: "0"
        return "/${settings.dashboard ?: "lovelace"}/$view"
    }

    /** State translations and default icons, in the HA user's language. */
    private suspend fun loadFrontendResources() {
        try {
            val config = hass.getConfig()
            player.haUrls = listOfNotNull(config.string("internal_url"), config.string("external_url"))
            val language = config.string("language") ?: formatter.language
            val translations = hass.getTranslations(language, "entity_component")
            formatter.setTranslations(language, translations)
        } catch (e: Exception) {
            Log.w(TAG, "loading translations failed: ${e.message}")
        }
        try {
            val result = hass.command(jsonOf("type" to "frontend/get_icons", "category" to "entity_component"))
            result.asObject()?.get("resources").asObject()?.let { icons.setComponentIcons(it) }
        } catch (e: Exception) {
            Log.w(TAG, "loading icons failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DashboardRuntime"

        @Volatile private var instance: DashboardRuntime? = null

        fun get(context: Context): DashboardRuntime =
            instance ?: synchronized(this) {
                instance ?: DashboardRuntime(context.applicationContext).also { instance = it }
            }
    }
}
