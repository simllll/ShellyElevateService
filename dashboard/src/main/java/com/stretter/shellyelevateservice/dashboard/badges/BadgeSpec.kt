package com.stretter.shellyelevateservice.dashboard.badges

import androidx.compose.runtime.Immutable
import com.stretter.shellyelevateservice.dashboard.json.array
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A parsed view badge (`badges:` of a view, HA 2024.8+ badge style).
 *
 * Parsing follows the frontend: `ensureBadgeConfig` (a plain string is an entity badge with its
 * name, an object without `type` an entity badge), `state-label` badges become entity badges,
 * entity badge defaults (`show_name: false`, `show_state: true`, `show_icon: true`) and the
 * legacy `display_type` migration. Badges with `disabled: true` are dropped.
 */
@Immutable
sealed interface BadgeSpec {
    /** The normalized config (actions and `visibility` are read from it). */
    val config: JsonObject

    /** `hui-entity-badge`. */
    @Immutable
    data class Entity(
        override val config: JsonObject,
        val entityId: String?,
        val name: String?,
        val icon: String?,
        val color: String?,
        val showName: Boolean,
        val showState: Boolean,
        val showIcon: Boolean,
        val showEntityPicture: Boolean,
    ) : BadgeSpec

    /** `custom:mushroom-template-badge`: every field may be a Jinja template. */
    @Immutable
    data class MushroomTemplate(
        override val config: JsonObject,
        val entityId: String?,
        val icon: String?,
        val color: String?,
        val label: String?,
        val content: String?,
        val picture: String?,
    ) : BadgeSpec {
        /** Template variables Mushroom passes to `render_template`. */
        val variables: JsonObject = jsonOf("config" to config, "entity" to entityId)
    }

    /** Any other badge type; rendered as a small chip with the type name. */
    @Immutable
    data class Unsupported(override val config: JsonObject, val type: String) : BadgeSpec

    companion object {
        /** All badges of a view, in order. */
        fun parseAll(view: JsonObject): List<BadgeSpec> = view.array("badges")?.mapNotNull { parse(it) } ?: emptyList()

        /** One badge config; null for unusable entries (null, numbers, `disabled`). */
        fun parse(element: JsonElement?): BadgeSpec? {
            val config = ensureBadgeConfig(element) ?: return null
            if (config.bool("disabled") == true) return null
            return when (val type = config.string("type")) {
                "entity" -> entity(config)
                "state-label" -> entity(
                    jsonOf(
                        "type" to "entity",
                        "entity" to config.string("entity"),
                        "color" to config.string("color"),
                        "show_name" to (config.bool("show_name") ?: true),
                        "visibility" to config["visibility"],
                    )
                )
                "custom:mushroom-template-badge" -> MushroomTemplate(
                    config = config,
                    entityId = config.string("entity"),
                    icon = config.string("icon"),
                    color = config.string("color"),
                    label = config.string("label"),
                    content = config.string("content"),
                    picture = config.string("picture"),
                )
                else -> Unsupported(config, type ?: "badge")
            }
        }

        /** `ensureBadgeConfig`. */
        private fun ensureBadgeConfig(element: JsonElement?): JsonObject? {
            element.asObject()?.let { o ->
                return if (o.string("type").isNullOrEmpty()) JsonObject(o + jsonOf("type" to "entity")) else o
            }
            val entity = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
            return jsonOf("type" to "entity", "entity" to entity, "show_name" to true)
        }

        /** Entity badge defaults plus `migrateLegacyEntityBadgeConfig` (`display_type`). */
        private fun entity(config: JsonObject): Entity {
            val displayType = config.string("display_type")
            val showName = config.bool("show_name") ?: (displayType == "complete")
            val showState = config.bool("show_state") ?: (displayType != "minimal")
            return Entity(
                config = config,
                entityId = config.string("entity"),
                name = config.string("name"),
                icon = config.string("icon")?.takeIf { it.isNotBlank() },
                color = config.string("color")?.takeIf { it.isNotBlank() },
                showName = showName,
                showState = showState,
                showIcon = config.bool("show_icon") ?: true,
                showEntityPicture = config.bool("show_entity_picture") ?: false,
            )
        }

        /**
         * Text of a template result as the browser shows it inside a `<span>`: whitespace runs
         * (template line breaks and indentation) collapse to one space, ends trimmed.
         */
        fun collapseWhitespace(text: String?): String? =
            text?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }

        private val WHITESPACE = Regex("\\s+")
    }
}
