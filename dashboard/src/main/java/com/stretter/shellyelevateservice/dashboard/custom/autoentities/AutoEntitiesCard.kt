package com.stretter.shellyelevateservice.dashboard.custom.autoentities

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.cards.CardRenderer
import com.stretter.shellyelevateservice.dashboard.cards.RenderCard
import com.stretter.shellyelevateservice.dashboard.cards.WatchEntities
import com.stretter.shellyelevateservice.dashboard.cards.template
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.hass.RegistryEntry
import com.stretter.shellyelevateservice.dashboard.hass.TemplateResult
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject

/**
 * `custom:auto-entities` (thomasloven): builds the entity list from `entities`, `filter.template`
 * (rendered server-side) and `filter.include`, removes `filter.exclude` matches, sorts and wraps
 * the result into `card` (default `entities`), or shows `else` when empty.
 *
 * Only what the filters need is observed: template-only configs (the common case) watch just the
 * entities the template returned; `include` rules watch all states. The entity registry is
 * fetched per entity, and only for rules that need it (`hidden_by`, `label`, …).
 * Re-evaluation is throttled like auto-entities' own 500 ms cooldown.
 */
class AutoEntitiesCard(config: JsonObject) : CardRenderer {
    private val spec = AutoEntitiesSpec(config)

    /** Resolved entries; null until the first evaluation (the card stays hidden until then). */
    private val entries = mutableStateOf<List<JsonObject>?>(null)

    @Composable
    override fun Effects(ctx: CardContext) {
        val templateResult = spec.template?.let { ctx.template(it) }
        if (spec.template != null && templateResult == null) return
        val templateEntries = remember(templateResult) {
            (templateResult as? TemplateResult.Value)?.let { AutoEntitiesSpec.parseTemplateResult(it.result) } ?: emptyList()
        }
        val candidateIds = remember(templateEntries) {
            (spec.staticEntries + templateEntries).mapNotNull { it.string("entity") }.distinct()
        }
        val needsAll = spec.needsAllStates
        if (needsAll) {
            DisposableEffect(Unit) {
                val handle = ctx.hass.watchAllEntities()
                onDispose { handle.close() }
            }
        } else {
            ctx.WatchEntities(candidateIds)
        }
        LaunchedEffect(templateEntries, needsAll) {
            val registry = ctx.hass.entityRegistry
            if (spec.usesLabels) registry.requestLabels()
            val states: Flow<Map<String, EntityState>> = when {
                needsAll -> ctx.hass.allStates
                candidateIds.isEmpty() -> flowOf(emptyMap())
                else -> combine(candidateIds.map { ctx.hass.entity(it) }) { arr ->
                    buildMap { arr.forEach { s -> if (s != null) put(s.entityId, s) } }
                }
            }
            val registryFlow = if (spec.usesRegistry) registry.entries else flowOf(emptyMap())
            val labelsFlow = if (spec.usesLabels) registry.labels else flowOf(emptyMap())
            // States arrive shortly after subscribing; give them a moment so excluded entities
            // (e.g. unavailable) do not flash up.
            val settled = flow {
                emit(false)
                delay(SETTLE_MS)
                emit(true)
            }
            combine(states, registryFlow, labelsFlow, settled) { s, r, l, timedOut -> Inputs(s, r, l, timedOut) }
                .conflate()
                .collect { input ->
                    if (!needsAll && !input.timedOut && candidateIds.any { it !in input.states }) return@collect
                    if (spec.usesLabels && input.labels == null && !input.timedOut) return@collect
                    val env = FilterEnv(
                        states = input.states,
                        registry = input.registry,
                        labelNames = input.labels ?: emptyMap(),
                        formatState = { ctx.formatter.formatState(it, System.currentTimeMillis()) },
                    )
                    // Include rules scan every state: keep that off the main thread.
                    val result = if (needsAll) withContext(Dispatchers.Default) { spec.resolve(templateEntries, env) }
                    else spec.resolve(templateEntries, env)
                    if (env.missingRegistry.isNotEmpty()) {
                        // Evaluated again when the entries arrive (registry flow update).
                        registry.request(env.missingRegistry)
                        return@collect
                    }
                    if (entries.value != result) entries.value = result
                    delay(THROTTLE_MS)
                }
        }
    }

    private class Inputs(
        val states: Map<String, EntityState>,
        val registry: Map<String, RegistryEntry?>,
        val labels: Map<String, String>?,
        val timedOut: Boolean,
    )

    @Composable
    override fun isVisible(ctx: CardContext): Boolean {
        val list = entries.value ?: return false
        return spec.displayedCard(list) != null
    }

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val list = entries.value ?: return
        val card = remember(list) { spec.displayedCard(list) } ?: return
        ctx.RenderCard(card, modifier)
    }

    private companion object {
        const val THROTTLE_MS = 500L
        const val SETTLE_MS = 1_500L
    }
}
