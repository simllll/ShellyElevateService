package io.github.simllll.panelelevate.dashboard.custom

import io.github.simllll.panelelevate.dashboard.custom.autoentities.AutoEntitiesSpec
import io.github.simllll.panelelevate.dashboard.custom.autoentities.EntityFilter
import io.github.simllll.panelelevate.dashboard.custom.autoentities.FilterEnv
import io.github.simllll.panelelevate.dashboard.custom.autoentities.SortSpec
import io.github.simllll.panelelevate.dashboard.custom.autoentities.ValueMatcher
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.hass.RegistryEntry
import io.github.simllll.panelelevate.dashboard.json.HaJson
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEntitiesFilterTest {
    private fun json(s: String): JsonObject = HaJson.parseToJsonElement(s).jsonObject
    private fun m(pattern: String) = ValueMatcher(JsonPrimitive(pattern))

    private fun state(id: String, state: String, vararg attrs: Pair<String, Any?>, lastChanged: Long = 0) =
        EntityState(id, state, jsonOf(*attrs), lastChanged, lastChanged)

    private fun env(vararg states: EntityState, registry: Map<String, RegistryEntry?> = emptyMap(), now: Long = 0) =
        FilterEnv(states.associateBy { it.entityId }, registry, nowMs = now)

    private fun ids(entries: List<JsonObject>) = entries.map { it.string("entity") }

    @Test
    fun `matcher plain, wildcard and regex`() {
        assertTrue(m("off").matches("off"))
        assertFalse(m("off").matches("on"))
        assertFalse(m("off").matches(null))
        assertTrue(m("*Eingang*").matches("Licht Eingang Decke"))
        assertFalse(m("*Eingang*").matches("Küche"))
        assertTrue(m("Power Meter*").matches("Power Meter Trockner"))
        assertFalse(m("Power Meter*").matches("Abstellraum Power Meter"))
        assertTrue(m("light.*").matches("light.kueche"))
        assertTrue(m("/^sensor\\..*_temp/").matches("sensor.bad_temperature"))
        assertFalse(m("/^sensor\\..*_temp/").matches("light.bad_temperature"))
        // Wildcards only match strings.
        assertFalse(m("*1*").matches(1.0))
    }

    @Test
    fun `matcher numeric comparisons follow parseFloat`() {
        assertTrue(m("= 0").matches(0.0))
        assertFalse(m("= 0").matches(2.0))
        assertTrue(m("= 0").matches("0"))
        assertTrue(m("> 5").matches("7.5 W"))
        assertFalse(m("> 5").matches("unavailable"))
        assertTrue(m("<= 5").matches(5.0))
        assertTrue(m(">= 5").matches(5.0))
        assertTrue(m("!= 3").matches(4.0))
        // NaN != 3 is true in JavaScript too.
        assertTrue(m("!= 3").matches("abc"))
        assertTrue(m("== 2").matches("2"))
    }

    @Test
    fun `matcher literals, stringify and ago`() {
        assertTrue(ValueMatcher(JsonPrimitive(0)).matches(0.0))
        assertFalse(ValueMatcher(JsonPrimitive(0)).matches("0"))
        assertTrue(ValueMatcher(JsonPrimitive(true)).matches(true))
        assertTrue(m("$$*\"a\"*").matches(HaJson.parseToJsonElement("""["a","b"]""")))
        val now = 10 * 60_000L
        assertTrue(m("< 5 m ago").matches((now - 2 * 60_000L).toDouble(), now))
        assertFalse(m("< 5 m ago").matches((now - 7 * 60_000L).toDouble(), now))
        assertTrue(m("> 1 h ago").matches("1970-01-01T00:00:00Z", 2 * 3_600_000L))
    }

    @Test
    fun `filter rules`() {
        val e = env(
            state("light.a", "on", "friendly_name" to "Küche Licht", "brightness" to 255, "nested" to mapOf("x" to "y")),
            state("binary_sensor.door", "on", "device_class" to "door"),
            state("group.g", "on", "entity_id" to listOf("light.a")),
        )
        assertTrue(EntityFilter.compile(json("""{"domain": "light"}""")).matches("light.a", e))
        assertTrue(EntityFilter.compile(json("""{"domain": "light", "state": "on"}""")).matches("light.a", e))
        assertFalse(EntityFilter.compile(json("""{"domain": "light", "state": "off"}""")).matches("light.a", e))
        assertTrue(EntityFilter.compile(json("""{"name": "Küche*"}""")).matches("light.a", e))
        assertTrue(EntityFilter.compile(json("""{"attributes": {"brightness": "> 100", "nested:x": "y"}}""")).matches("light.a", e))
        assertTrue(EntityFilter.compile(json("""{"attributes": {"device_class": "door"}}""")).matches("binary_sensor.door", e))
        assertTrue(EntityFilter.compile(json("""{"group": "group.g"}""")).matches("light.a", e))
        assertTrue(EntityFilter.compile(json("""{"not": {"domain": "switch"}}""")).matches("light.a", e))
        assertTrue(EntityFilter.compile(json("""{"or": [{"domain": "switch"}, {"domain": "light"}]}""")).matches("light.a", e))
        assertFalse(EntityFilter.compile(json("""{"and": [{"domain": "switch"}, {"domain": "light"}]}""")).matches("light.a", e))
        // `options` alone is no rule: such a filter never matches; unknown entities never match.
        assertFalse(EntityFilter.compile(json("""{"options": {}}""")).matches("light.a", e))
        assertFalse(EntityFilter.compile(json("""{"domain": "light"}""")).matches("light.missing", e))
        // Duplicate keys are written with a suffix ("state 2").
        assertTrue(EntityFilter.compile(json("""{"state 1": "on", "state 2": "/o/"}""")).matches("light.a", e))
    }

    @Test
    fun `state rule also matches the formatted state`() {
        val e = FilterEnv(mapOf("switch.a" to state("switch.a", "off")), formatState = { "Aus" })
        assertTrue(EntityFilter.compile(json("""{"state": "Aus"}""")).matches("switch.a", e))
    }

    @Test
    fun `registry rules record missing entries`() {
        val filter = EntityFilter.compile(json("""{"hidden_by": "user"}"""))
        assertTrue(filter.usesRegistry)
        val unknown = env(state("light.a", "on"))
        assertFalse(filter.matches("light.a", unknown))
        assertEquals(setOf("light.a"), unknown.missingRegistry)

        val known = env(
            state("light.a", "on"), state("light.b", "on"), state("light.c", "on"),
            registry = mapOf("light.a" to RegistryEntry("light.a", hiddenBy = "user"), "light.b" to RegistryEntry("light.b"), "light.c" to null),
        )
        assertTrue(filter.matches("light.a", known))
        assertFalse(filter.matches("light.b", known))
        assertFalse(filter.matches("light.c", known))
        assertTrue(known.missingRegistry.isEmpty())
    }

    @Test
    fun `registry lookups are skipped when cheaper rules fail`() {
        val filter = EntityFilter.compile(json("""{"hidden_by": "user", "state": "unavailable"}"""))
        val e = env(state("light.a", "on"))
        assertFalse(filter.matches("light.a", e))
        assertTrue(e.missingRegistry.isEmpty())
    }

    @Test
    fun `label and integration rules`() {
        val e = FilterEnv(
            mapOf("switch.a" to state("switch.a", "on")),
            mapOf("switch.a" to RegistryEntry("switch.a", labels = listOf("long_running"), platform = "shelly")),
            labelNames = mapOf("long_running" to "Long-running"),
        )
        assertTrue(EntityFilter.compile(json("""{"label": "Long-running"}""")).matches("switch.a", e))
        assertTrue(EntityFilter.compile(json("""{"label": "long_running"}""")).matches("switch.a", e))
        assertTrue(EntityFilter.compile(json("""{"integration": "shelly"}""")).matches("switch.a", e))
        assertFalse(EntityFilter.compile(json("""{"integration": "zha"}""")).matches("switch.a", e))
    }

    @Test
    fun `template results`() {
        assertEquals(
            listOf("light.a", "light.b"),
            ids(AutoEntitiesSpec.parseTemplateResult(JsonPrimitive("light.a, light.b\n"))),
        )
        val list = AutoEntitiesSpec.parseTemplateResult(HaJson.parseToJsonElement("""["light.a", {"entity": "switch.x", "secondary_info": "last-changed"}]"""))
        assertEquals(listOf("light.a", "switch.x"), ids(list))
        assertEquals("last-changed", list[1].string("secondary_info"))
        // A list rendered as text.
        assertEquals(listOf("light.a"), ids(AutoEntitiesSpec.parseTemplateResult(JsonPrimitive("""[{"entity": "light.a"}]"""))))
        assertTrue(AutoEntitiesSpec.parseTemplateResult(JsonPrimitive("")).isEmpty())
    }

    /** The "Aktive Geräte" card of the dashboard. */
    @Test
    fun `dashboard exclude rules`() {
        val spec = AutoEntitiesSpec(
            json(
                """{"type": "custom:auto-entities", "show_empty": false, "unique": "entity",
                "card": {"type": "entities", "title": "Aktive Geräte"},
                "else": {"type": "markdown", "content": "Keine Geräte aktiv"},
                "filter": {"template": "…", "exclude": [
                  {"options": {}, "state": "off"}, {"options": {}, "state": "idle"},
                  {"options": {}, "name": "*Eingang*"}, {"options": {}, "state": "unavailable"},
                  {"options": {}, "hidden_by": "integration"}, {"options": {}, "hidden_by": "user"},
                  {"options": {}, "name": "Power Meter*"}]}}"""
            )
        )
        val template = AutoEntitiesSpec.parseTemplateResult(
            HaJson.parseToJsonElement(
                """[{"type": "custom:timer-bar-card", "entity": "switch.dishwasher"},
                {"entity": "switch.eingang"}, {"entity": "switch.pm"}, {"entity": "media_player.tv", "type": "simple-entity"},
                {"entity": "media_player.speaker"}, {"entity": "switch.hidden"}, {"entity": "switch.hidden"}, {"entity": "fan.x"}]"""
            )
        )
        val states = listOf(
            state("switch.dishwasher", "on", "friendly_name" to "Geschirrspüler"),
            state("switch.eingang", "on", "friendly_name" to "Licht Eingang"),
            state("switch.pm", "on", "friendly_name" to "Power Meter Trockner"),
            state("media_player.tv", "off"),
            state("media_player.speaker", "idle"),
            state("switch.hidden", "on"),
            state("fan.x", "unavailable"),
        )
        val registry = states.associate { it.entityId to RegistryEntry(it.entityId) } +
            ("switch.hidden" to RegistryEntry("switch.hidden", hiddenBy = "integration"))
        val e = FilterEnv(states.associateBy { it.entityId }, registry)
        val result = spec.resolve(template, e)
        assertEquals(listOf("switch.dishwasher"), ids(result))
        assertEquals("custom:timer-bar-card", result[0].string("type"))
        assertTrue(e.missingRegistry.isEmpty())
        assertEquals("entities", spec.displayedCard(result)?.string("type"))
        assertEquals("Aktive Geräte", spec.displayedCard(result)?.string("title"))
        // Empty → else card.
        assertEquals("markdown", spec.displayedCard(emptyList())?.string("type"))
    }

    @Test
    fun `wrapped card config, show_empty and empty types`() {
        val spec = AutoEntitiesSpec(json("""{"filter": {"template": "x"}, "card": {"type": "entities", "title": "T"}}"""))
        val card = spec.cardConfig(listOf(jsonOf("entity" to "light.a")))
        assertEquals("entities", card.string("type"))
        assertEquals("T", card.string("title"))
        assertEquals("""[{"entity":"light.a"}]""", card["entities"].toString())
        // Without show_empty: false or else, an empty result still shows the card.
        assertEquals("entities", spec.displayedCard(emptyList())?.string("type"))
        val hidden = AutoEntitiesSpec(json("""{"filter": {"template": "x"}, "show_empty": false}"""))
        assertNull(hidden.displayedCard(emptyList()))
        assertNull(hidden.displayedCard(listOf(jsonOf("type" to "section"), jsonOf("type" to "divider"))))
        val param = AutoEntitiesSpec(json("""{"filter": {"template": "x"}, "card_param": "cards", "card": {"type": "grid"}}"""))
        assertEquals("grid", param.cardConfig(emptyList()).string("type"))
        assertTrue(param.cardConfig(emptyList()).containsKey("cards"))
    }

    @Test
    fun `includes with options, per-filter sort and slicing`() {
        val spec = AutoEntitiesSpec(
            json(
                """{"filter": {"include": [
                  {"domain": "light", "options": {"secondary_info": "this.entity_id", "tap_action": {"action": "toggle"}}},
                  {"type": "section", "label": "Sensoren"},
                  {"domain": "sensor", "sort": {"method": "state", "numeric": true, "reverse": true}, "options": {}}
                ]}, "sort": {"count": 4}}"""
            )
        )
        assertTrue(spec.needsAllStates)
        val e = env(
            state("light.b", "on"), state("light.a", "off"),
            state("sensor.t1", "20"), state("sensor.t2", "25"), state("sensor.t3", "15"),
        )
        val result = spec.resolve(emptyList(), e)
        assertEquals(listOf("light.a", "light.b", null, "sensor.t2"), ids(result))
        assertEquals("light.a", result[0].string("secondary_info"))
        assertEquals("section", result[2].string("type"))
    }

    @Test
    fun `sort methods`() {
        val states = listOf(
            state("light.b", "on", "friendly_name" to "beta"),
            state("light.a", "off", "friendly_name" to "Alpha"),
            state("light.c", "on", "friendly_name" to "gamma", lastChanged = 5),
        ).associateBy { it.entityId }
        val entries = listOf("light.c", "light.missing", "light.b", "light.a").map { jsonOf("entity" to it) }
        fun sorted(spec: String) = ids(SortSpec(json(spec)).sort(entries, states))
        assertEquals(listOf("light.a", "light.b", "light.c", "light.missing"), sorted("""{"method": "name", "ignore_case": true}"""))
        // Like auto-entities, entries without a value sort first when reversed.
        assertEquals(listOf("light.missing", "light.c", "light.b", "light.a"), sorted("""{"method": "name", "ignore_case": true, "reverse": true}"""))
        assertEquals(listOf("light.a", "light.b", "light.c", "light.missing"), sorted("""{"method": "entity_id"}"""))
        assertEquals(listOf("light.b", "light.a", "light.c", "light.missing"), sorted("""{"method": "last_changed"}"""))
        // Unknown methods keep the order.
        assertEquals(ids(entries), sorted("""{"method": "device"}"""))
    }

    @Test
    fun `numeric sort keeps non-numbers last`() {
        val s = SortSpec(json("""{"method": "state", "numeric": true}"""))
        assertTrue(s.compare("2", "10") < 0)
        assertTrue(s.compare("abc", "10") > 0)
        assertEquals(0, s.compare(null, null))
    }

    @Test
    fun `unique by entity and by value`() {
        val byEntity = AutoEntitiesSpec(json("""{"entities": [{"entity": "light.a"}, {"entity": "light.a", "name": "x"}, "light.b"], "unique": "entity"}"""))
        assertEquals(listOf("light.a", "light.b"), ids(byEntity.resolve(emptyList(), env())))
        val byValue = AutoEntitiesSpec(json("""{"entities": [{"entity": "light.a"}, {"entity": "light.a", "name": "x"}, "light.a"], "unique": true}"""))
        assertEquals(2, byValue.resolve(emptyList(), env()).size)
    }

    @Test
    fun `options replace this entity_id`() {
        val out = AutoEntitiesSpec.applyOptions(
            jsonOf("entity" to "sensor.x"),
            json("""{"type": "custom:template-entity-row", "state": "{{ states('this.entity_id') }}"}"""),
        )
        assertEquals("{{ states('sensor.x') }}", out.string("state"))
        assertEquals("sensor.x", out.string("entity"))
    }
}
