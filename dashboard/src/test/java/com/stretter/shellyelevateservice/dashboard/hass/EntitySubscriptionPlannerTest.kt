package com.stretter.shellyelevateservice.dashboard.hass

import com.stretter.shellyelevateservice.dashboard.hass.EntitySubscriptionPlanner.Step
import com.stretter.shellyelevateservice.dashboard.hass.EntitySubscriptionPlanner.Sub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitySubscriptionPlannerTest {

    /** Applies steps like HassClient does and returns the resulting subscriptions. */
    private fun apply(current: List<Sub>, steps: List<Step>, nextKey: Int = 100): List<Sub> {
        var key = nextKey
        val result = current.toMutableList()
        steps.forEach { step ->
            when (step) {
                is Step.Subscribe -> result += Sub(key++, step.ids)
                is Step.Unsubscribe -> result.removeAll { it.key == step.key }
            }
        }
        return result
    }

    @Test
    fun `first acquire subscribes the ids`() {
        val p = EntitySubscriptionPlanner()
        p.acquire(listOf("light.a", "light.b"))
        assertEquals(listOf(Step.Subscribe(setOf("light.a", "light.b"))), p.plan(emptyList()))
    }

    @Test
    fun `growing subscribes only the missing ids`() {
        val p = EntitySubscriptionPlanner()
        p.acquire(listOf("light.a"))
        val subs = apply(emptyList(), p.plan(emptyList()))
        p.acquire(listOf("light.a", "cover.x"))
        assertEquals(listOf(Step.Subscribe(setOf("cover.x"))), p.plan(subs))
    }

    @Test
    fun `ref counting keeps ids until the last release`() {
        val p = EntitySubscriptionPlanner()
        p.acquire(listOf("light.a"))
        p.acquire(listOf("light.a"))
        val subs = apply(emptyList(), p.plan(emptyList()))
        p.release(listOf("light.a"))
        assertTrue(p.plan(subs).isEmpty())
        p.release(listOf("light.a"))
        assertEquals(listOf(Step.Unsubscribe(subs.single().key)), p.plan(subs))
    }

    @Test
    fun `a subscription stays while any of its ids is needed`() {
        val p = EntitySubscriptionPlanner()
        p.acquire(listOf("light.a", "light.b"))
        val subs = apply(emptyList(), p.plan(emptyList()))
        p.release(listOf("light.a"))
        assertTrue(p.plan(subs).isEmpty())
    }

    @Test
    fun `fragmented subscriptions are consolidated`() {
        val p = EntitySubscriptionPlanner(maxSubscriptions = 2)
        var subs = emptyList<Sub>()
        var key = 1
        for (id in listOf("a.1", "a.2", "a.3")) {
            p.acquire(listOf(id))
            subs = apply(subs, p.plan(subs), key)
            key += 10
        }
        assertEquals(1, subs.size)
        assertEquals(setOf("a.1", "a.2", "a.3"), subs.single().ids)
    }

    @Test
    fun `all mode uses one unfiltered subscription`() {
        val p = EntitySubscriptionPlanner()
        p.acquire(listOf("light.a"))
        var subs = apply(emptyList(), p.plan(emptyList()), 1)
        p.acquireAll()
        subs = apply(subs, p.plan(subs), 10)
        assertEquals(listOf<Set<String>?>(null), subs.map { it.ids })
        p.releaseAll()
        subs = apply(subs, p.plan(subs), 20)
        assertEquals(listOf<Set<String>?>(setOf("light.a")), subs.map { it.ids })
    }
}
