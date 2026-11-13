package io.github.simllll.panelelevate.dashboard.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import io.github.simllll.panelelevate.dashboard.actions.ActionHandler
import io.github.simllll.panelelevate.dashboard.format.StateFormatter
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.hass.HassClient
import io.github.simllll.panelelevate.dashboard.hass.TemplateResult
import io.github.simllll.panelelevate.dashboard.icons.IconResolver
import io.github.simllll.panelelevate.dashboard.image.ImageLoader
import io.github.simllll.panelelevate.dashboard.popup.PopupHost
import io.github.simllll.panelelevate.dashboard.rows.RowRegistry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** Where a card is placed; mirrors the frontend's `card.layout`. */
enum class CardLayout {
    /** Directly in a grid section: fixed `rows` give an exact height. */
    GRID,
    /** Inside a stack, popup or other container: content height. */
    STACK,
}

/**
 * Everything a card needs. One instance per dashboard; [withLayout] derives variants.
 *
 * Composable helpers ([entity], [template], [RenderCard], [now]) are extension functions below.
 */
@Stable
class CardContext(
    val hass: HassClient,
    val cards: CardRegistry,
    val rows: RowRegistry,
    val actions: ActionHandler,
    val popups: PopupHost,
    val images: ImageLoader,
    val formatter: StateFormatter,
    val icons: IconResolver,
    /** Wall clock in ms, ticking every 30 s; drives relative times. */
    val clock: StateFlow<Long>,
    val layout: CardLayout = CardLayout.GRID,
) {
    fun withLayout(layout: CardLayout): CardContext =
        if (layout == this.layout) this else CardContext(hass, cards, rows, actions, popups, images, formatter, icons, clock, layout)
}

/**
 * Live state of [entityId] (null while unknown/missing). Subscribes the entity while this
 * composable is in the composition; only callers of this function recompose on changes.
 */
@Composable
fun CardContext.entity(entityId: String?): EntityState? {
    if (entityId.isNullOrBlank()) return null
    DisposableEffect(entityId) {
        val handle = hass.watchEntities(listOf(entityId))
        onDispose { handle.close() }
    }
    val state by remember(entityId) { hass.entity(entityId) }.collectAsState()
    return state
}

/** Like [entity] but as a [State], for deferring reads into lambdas (draw/layout phases). */
@Composable
fun CardContext.entityState(entityId: String?): State<EntityState?> {
    DisposableEffect(entityId) {
        val handle = entityId?.let { hass.watchEntities(listOf(it)) }
        onDispose { handle?.close() }
    }
    val flow = remember(entityId) { entityId?.let { hass.entity(it) } }
    return flow?.collectAsState() ?: remember { NullState }
}

/** Subscribes several entities at once (e.g. a card with an entity list) without reading them. */
@Composable
fun CardContext.WatchEntities(entityIds: Collection<String>) {
    val key = remember(entityIds) { entityIds.toSortedSet().toList() }
    DisposableEffect(key) {
        val handle = hass.watchEntities(key)
        onDispose { handle.close() }
    }
}

/**
 * Renders [template] via `render_template` (shared, live). Returns null until the first result.
 * Plain strings without template syntax are returned as-is without a round trip.
 */
@Composable
fun CardContext.template(template: String?, variables: JsonObject? = null): TemplateResult? {
    if (template == null) return null
    if (!isTemplate(template)) return remember(template) { TemplateResult.Value(kotlinx.serialization.json.JsonPrimitive(template)) }
    val flow = remember(template, variables) { hass.renderTemplate(template, variables) }
    val result by flow.collectAsState(initial = null)
    return result
}

/** Current time in ms, recomposing the caller every 30 s. */
@Composable
fun CardContext.now(): Long {
    val now by clock.collectAsState()
    return now
}

/**
 * Renders a nested card (stacks, popups). Emits exactly one layout node while the card is visible
 * and none while hidden, so containers can rely on one child per visible card; min constraints
 * (stretching) pass through to the card.
 */
@Composable
fun CardContext.RenderCard(config: JsonObject, modifier: Modifier = Modifier) {
    val renderer = remember(config) { cards.create(config) }
    renderer.Effects(this)
    if (!renderer.isVisible(this)) return
    if (!conditionsMet(config)) return
    Box(modifier, propagateMinConstraints = true) { renderer.Content(this@RenderCard, Modifier) }
}

/** True if [text] contains Jinja syntax (`isTemplate` in the frontend). */
fun isTemplate(text: String): Boolean = text.contains("{{") || text.contains("{%") || text.contains("{#")

private object NullState : State<EntityState?> {
    override val value: EntityState? get() = null
}

/** Collects a one-shot suspend loader as state (null until done). */
@Composable
fun <T> rememberLoaded(vararg keys: Any?, loader: suspend () -> T?): T? {
    val state by produceState<T?>(null, *keys) { value = loader() }
    return state
}
