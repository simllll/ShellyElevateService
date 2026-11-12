package com.stretter.shellyelevateservice.dashboard.lovelace

import com.stretter.shellyelevateservice.dashboard.json.asBoolean
import com.stretter.shellyelevateservice.dashboard.json.asDouble
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.serialization.json.JsonObject

/**
 * The dashboard's `swipe_nav` config (zanna-37/hass-swipe-navigation): a horizontal swipe longer
 * than [swipeAmount] percent of the screen width goes to the next (swipe left) or previous view;
 * [wrap] continues from the last view at the first. Like the plugin, subviews and hidden views
 * are skipped and swipes starting on a view outside that list do nothing.
 * Without `swipe_nav` swiping is off.
 */
data class SwipeNavConfig(
    val wrap: Boolean = true,
    val animate: Animate = Animate.NONE,
    val swipeAmount: Float = 15f,
    val skipHidden: Boolean = true,
) {
    enum class Animate { NONE, SWIPE, FADE }

    /**
     * Index of the view a swipe leads to from [current]: [direction] +1 = next (swipe to the left),
     * -1 = previous. Null if there is none (edge without [wrap], current view not swipeable).
     */
    fun target(views: List<JsonObject>, current: Int, direction: Int): Int? {
        val swipeable = views.indices.filter { i -> views[i].bool("subview") != true && !(skipHidden && views[i]["visible"].asBoolean() == false) }
        val pos = swipeable.indexOf(current)
        if (pos < 0 || swipeable.size < 2) return null
        val next = pos + direction
        return when {
            next in swipeable.indices -> swipeable[next]
            !wrap -> null
            else -> swipeable[(next + swipeable.size) % swipeable.size]
        }
    }

    companion object {
        /** Null when the dashboard has no `swipe_nav` (or `swipe_nav: false`). */
        fun parse(dashboard: JsonObject): SwipeNavConfig? {
            val element = dashboard["swipe_nav"] ?: return null
            if (element.asBoolean() == false) return null
            val c = element as? JsonObject ?: return SwipeNavConfig()
            return SwipeNavConfig(
                wrap = c.bool("wrap") ?: true,
                animate = when (c.string("animate")) {
                    "swipe" -> Animate.SWIPE
                    "fade", "flip" -> Animate.FADE
                    else -> Animate.NONE
                },
                swipeAmount = (c["swipe_amount"].asDouble()?.toFloat() ?: 15f).coerceIn(1f, 100f),
                skipHidden = c.bool("skip_hidden") ?: true,
            )
        }

        /** Index of the view [path] (a view `path`, else an index; null = first view); null if unknown. */
        fun viewIndexOrNull(views: List<JsonObject>, path: String?): Int? {
            if (views.isEmpty()) return null
            if (path == null) return 0
            views.indexOfFirst { it.string("path") == path }.takeIf { it >= 0 }?.let { return it }
            return path.toIntOrNull()?.takeIf { it in views.indices }
        }

        /** The path [Navigator] uses for view [index]: its `path`, else the index. */
        fun pathOf(views: List<JsonObject>, index: Int): String = views[index].string("path") ?: index.toString()
    }
}
