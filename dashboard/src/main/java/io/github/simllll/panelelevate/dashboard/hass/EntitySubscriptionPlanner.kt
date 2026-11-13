package io.github.simllll.panelelevate.dashboard.hass

/**
 * Ref-counts the entity ids the UI needs and decides which `subscribe_entities` subscriptions
 * (each with an `entity_ids` filter) should exist.
 *
 * Growing the set adds a subscription for just the missing ids, so opening a popup does not make
 * HA resend every state on the screen. A subscription is dropped once none of its ids is needed.
 * When the subscriptions get fragmented they are merged into one.
 *
 * Pure logic, not thread-safe: [HassClient] calls it from its single protocol coroutine.
 */
class EntitySubscriptionPlanner(private val maxSubscriptions: Int = 6) {

    /** A subscription as the planner sees it. `ids == null` means unfiltered (all entities). */
    data class Sub(val key: Int, val ids: Set<String>?)

    sealed interface Step {
        data class Subscribe(val ids: Set<String>?) : Step
        data class Unsubscribe(val key: Int) : Step
    }

    private val counts = HashMap<String, Int>()
    private var allCount = 0

    val required: Set<String> get() = counts.keys
    val wantsAll: Boolean get() = allCount > 0

    fun acquire(ids: Collection<String>) = ids.forEach { counts[it] = (counts[it] ?: 0) + 1 }

    fun release(ids: Collection<String>) = ids.forEach { id ->
        val n = (counts[id] ?: return@forEach) - 1
        if (n <= 0) counts.remove(id) else counts[id] = n
    }

    fun acquireAll() { allCount++ }
    fun releaseAll() { if (allCount > 0) allCount-- }

    /** Steps that turn [current] into a set of subscriptions covering [required]. */
    fun plan(current: List<Sub>): List<Step> {
        if (wantsAll) {
            if (current.any { it.ids == null }) {
                return current.filter { it.ids != null }.map { Step.Unsubscribe(it.key) }
            }
            return listOf(Step.Subscribe(null)) + current.map { Step.Unsubscribe(it.key) }
        }
        val needed = counts.keys
        val live = current.filter { sub -> sub.ids == null || sub.ids.any { it in needed } }
        val steps = mutableListOf<Step>()
        current.filter { it !in live }.forEach { steps += Step.Unsubscribe(it.key) }

        // An unfiltered subscription left over from "all" mode is replaced by a filtered one.
        val filteredLive = live.filter { it.ids != null }
        val unfiltered = live.filter { it.ids == null }
        val covered = filteredLive.flatMapTo(HashSet()) { it.ids!! }
        val missing = needed - covered

        if (unfiltered.isNotEmpty() || filteredLive.size + (if (missing.isEmpty()) 0 else 1) > maxSubscriptions) {
            // Consolidate: subscribe the full set first, then drop the old ones (no gap in updates).
            if (needed.isNotEmpty()) steps += Step.Subscribe(needed.toSet())
            live.forEach { steps += Step.Unsubscribe(it.key) }
            return steps
        }
        if (missing.isNotEmpty()) steps += Step.Subscribe(missing.toSet())
        return steps
    }
}
