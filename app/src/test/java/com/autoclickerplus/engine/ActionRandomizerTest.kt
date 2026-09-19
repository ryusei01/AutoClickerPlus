package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class ActionRandomizerTest {
    @Test
    fun waitUsesConfiguredJitterAndNeverGoesNegative() {
        val randomizer = ActionRandomizer(Random(1234))

        repeat(1_000) {
            assertEquals(0L, randomizer.waitMs(0, 30))
            assertTrue(randomizer.waitMs(500, 30) in 470L..530L)
            assertTrue(randomizer.waitMs(10, 30) in 0L..40L)
            assertEquals(500L, randomizer.waitMs(500, 0))
            assertTrue(randomizer.waitMs(1_000, 200) in 800L..1_200L)
        }
    }

    @Test
    fun tapStaysInsideConfiguredRadiusAndScreen() {
        val randomizer = ActionRandomizer(Random(42))
        val action = AutomationAction.Tap(x = 1f, y = 1f, jitterPx = 10)

        repeat(1_000) {
            val point = randomizer.tapPoint(action, ScreenBounds(100, 200))
            assertTrue(point.x in 0f..99f)
            assertTrue(point.y in 0f..199f)
            assertTrue(hypot(point.x - action.x, point.y - action.y) <= 10.001f)
        }
    }

    @Test
    fun swipeKeepsOriginalVector() {
        val randomizer = ActionRandomizer(Random(99))
        val action = AutomationAction.Swipe(
            startX = 50f,
            startY = 90f,
            endX = 50f,
            endY = 20f,
            jitterPx = 8,
        )

        repeat(100) {
            val (start, end) = randomizer.swipePoints(action, ScreenBounds(100, 100))
            assertEquals(action.endX - action.startX, end.x - start.x, 0.001f)
            assertEquals(action.endY - action.startY, end.y - start.y, 0.001f)
            assertTrue(start.x in 0f..99f && end.x in 0f..99f)
            assertTrue(start.y in 0f..99f && end.y in 0f..99f)
        }
    }
}
