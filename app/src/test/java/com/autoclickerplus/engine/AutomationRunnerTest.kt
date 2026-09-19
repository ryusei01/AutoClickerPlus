package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationRunnerTest {
    @Test
    fun executesActionsInOrderForRequestedLoopCount() = runTest {
        val calls = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val executor = object : GestureExecutor {
            override suspend fun tap(point: GesturePoint): Boolean {
                calls += "tap"
                return true
            }

            override suspend fun swipe(
                start: GesturePoint,
                end: GesturePoint,
                durationMs: Long,
            ): Boolean {
                calls += "swipe"
                return true
            }
        }
        val runner = AutomationRunner(
            scope = this,
            executor = executor,
            randomizer = ActionRandomizer(Random(7)),
            wait = { waits += it },
            waitForLoopBoundary = { calls += "boundary" },
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Tap(waitAfterMs = 100),
                AutomationAction.Swipe(waitAfterMs = 200),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 2,
        )

        assertTrue(runner.start(config, ScreenBounds(1080, 2400)))
        assertFalse(runner.start(config, ScreenBounds(1080, 2400)))
        advanceUntilIdle()

        assertEquals(listOf("tap", "swipe", "boundary", "tap", "swipe"), calls)
        assertEquals(4, waits.size)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun failedGestureStopsRemainingActions() = runTest {
        var callCount = 0
        val executor = object : GestureExecutor {
            override suspend fun tap(point: GesturePoint): Boolean {
                callCount++
                return false
            }

            override suspend fun swipe(
                start: GesturePoint,
                end: GesturePoint,
                durationMs: Long,
            ): Boolean {
                callCount++
                return true
            }
        }
        val runner = AutomationRunner(this, executor)
        val config = AutomationConfig(
            actions = listOf(AutomationAction.Tap(), AutomationAction.Swipe()),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(1, callCount)
        assertEquals(RunnerState.FAILED, runner.state.value)
    }

    @Test
    fun stopCancelsAnInfiniteSequence() = runTest {
        var callCount = 0
        val executor = object : GestureExecutor {
            override suspend fun tap(point: GesturePoint): Boolean {
                callCount++
                return true
            }

            override suspend fun swipe(
                start: GesturePoint,
                end: GesturePoint,
                durationMs: Long,
            ) = true
        }
        val runner = AutomationRunner(
            scope = this,
            executor = executor,
            wait = { awaitCancellation() },
        )
        val config = AutomationConfig(
            actions = listOf(AutomationAction.Tap()),
            repeatMode = RepeatMode.INFINITE,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        runCurrent()
        runner.stop()
        advanceUntilIdle()

        assertEquals(1, callCount)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }
}
