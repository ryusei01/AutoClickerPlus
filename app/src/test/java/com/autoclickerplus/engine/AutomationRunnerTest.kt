package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.ConditionOperator
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
                stopAtEnd: Boolean,
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
        assertEquals(6, waits.size)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun waitsForFullSwipeDurationBeforeNextAction() = runTest {
        val calls = mutableListOf<String>()
        var clock = 0L
        val executor = object : GestureExecutor {
            override suspend fun tap(point: GesturePoint): Boolean {
                calls += "tap"
                return true
            }

            override suspend fun swipe(
                start: GesturePoint,
                end: GesturePoint,
                durationMs: Long,
                stopAtEnd: Boolean,
            ): Boolean {
                calls += "swipe"
                clock += 40L
                return true
            }
        }
        val runner = AutomationRunner(
            scope = this,
            executor = executor,
            wait = { duration ->
                calls += "wait:$duration"
                clock += duration
            },
            waitForLoopBoundary = {},
            nowMs = { clock },
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Swipe(durationMs = 300L, waitAfterMs = 120L, stopAtEnd = false),
                AutomationAction.Tap(),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals("swipe", calls[0])
        assertEquals("wait:340", calls[1])
        assertTrue(calls[2].startsWith("wait:"))
        assertEquals("tap", calls[3])
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun fullScrollUsesRepeatedSwipePath() = runTest {
        val calls = mutableListOf<String>()
        val executor = object : GestureExecutor {
            override suspend fun tap(point: GesturePoint): Boolean = true

            override suspend fun swipe(
                start: GesturePoint,
                end: GesturePoint,
                durationMs: Long,
                stopAtEnd: Boolean,
            ): Boolean {
                calls += "swipe"
                return true
            }

            override suspend fun scrollToEnd(start: GesturePoint, end: GesturePoint): Boolean {
                calls += "scrollToEnd"
                return true
            }
        }
        val runner = AutomationRunner(
            scope = this,
            executor = executor,
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Swipe(
                    durationMs = 300L,
                    stopAtEnd = true,
                    fullScroll = true,
                    waitAfterMs = 0L,
                    waitJitterMs = 0,
                ),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("scrollToEnd"), calls)
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
                stopAtEnd: Boolean,
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
                stopAtEnd: Boolean,
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

    @Test
    fun executesNestedThenAndElseBranchesInOrder() = runTest {
        val tappedX = mutableListOf<Float>()
        val executor = object : GestureExecutor {
            override suspend fun tap(point: GesturePoint): Boolean {
                tappedX += point.x
                return true
            }

            override suspend fun swipe(
                start: GesturePoint,
                end: GesturePoint,
                durationMs: Long,
                stopAtEnd: Boolean,
            ) = true
        }
        val evaluator = ConditionEvaluator { conditions, _ ->
            (conditions.single() as AutomationCondition.TextExists).query == "true"
        }
        val nested = AutomationAction.IfBlock(
            conditions = listOf(AutomationCondition.TextExists(query = "false")),
            thenActions = listOf(AutomationAction.Tap(x = 100f, y = 100f)),
            elseActions = listOf(AutomationAction.Tap(x = 200f, y = 100f)),
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.IfBlock(
                    conditions = listOf(AutomationCondition.TextExists(query = "true")),
                    operator = ConditionOperator.AND,
                    thenActions = listOf(nested),
                    elseActions = listOf(AutomationAction.Tap(x = 50f, y = 100f)),
                ),
                AutomationAction.Tap(x = 300f, y = 100f),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )
        val runner = AutomationRunner(
            scope = this,
            executor = executor,
            conditionEvaluator = evaluator,
            wait = {},
            waitForLoopBoundary = {},
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(2, tappedX.size)
        assertTrue(tappedX[0] in 195f..205f)
        assertTrue(tappedX[1] in 295f..305f)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun conditionEvaluationErrorFailsSafely() = runTest {
        val executor = object : GestureExecutor {
            override suspend fun tap(point: GesturePoint) = true
            override suspend fun swipe(
                start: GesturePoint,
                end: GesturePoint,
                durationMs: Long,
                stopAtEnd: Boolean,
            ) = true
        }
        val runner = AutomationRunner(
            scope = this,
            executor = executor,
            conditionEvaluator = ConditionEvaluator { _, _ ->
                throw ConditionEvaluationException("unavailable")
            },
        )
        val config = AutomationConfig(
            actions = listOf(AutomationAction.IfBlock()),
            repeatMode = RepeatMode.COUNT,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(RunnerState.FAILED, runner.state.value)
    }

    @Test
    fun orMatchesWhenAnyConditionIsTrue() = runTest {
        val calls = mutableListOf<String>()
        val executor = recordingExecutor(calls)
        val runner = AutomationRunner(
            scope = this,
            executor = executor,
            conditionEvaluator = ConditionEvaluator { conditions, operator ->
                require(operator == ConditionOperator.OR)
                conditions.filterIsInstance<AutomationCondition.TextExists>()
                    .any { it.query == "yes" }
            },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.IfBlock(
                    conditions = listOf(
                        AutomationCondition.TextExists(query = "no"),
                        AutomationCondition.TextExists(query = "yes"),
                    ),
                    operator = ConditionOperator.OR,
                    thenActions = listOf(AutomationAction.Tap(x = 10f, y = 10f, jitterPx = 3)),
                    elseActions = listOf(AutomationAction.Tap(x = 90f, y = 10f, jitterPx = 3)),
                ),
            ),
            repeatMode = RepeatMode.COUNT,
        )
        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()
        assertEquals(listOf("then"), calls)
    }

    @Test
    fun andGoesToElseWhenAnyConditionIsFalse() = runTest {
        val calls = mutableListOf<String>()
        val runner = AutomationRunner(
            scope = this,
            executor = recordingExecutor(calls),
            conditionEvaluator = ConditionEvaluator { conditions, operator ->
                require(operator == ConditionOperator.AND)
                conditions.filterIsInstance<AutomationCondition.TextExists>()
                    .all { it.query == "yes" }
            },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.IfBlock(
                    conditions = listOf(
                        AutomationCondition.TextExists(query = "yes"),
                        AutomationCondition.TextExists(query = "no"),
                    ),
                    operator = ConditionOperator.AND,
                    thenActions = listOf(AutomationAction.Tap(x = 10f, y = 10f, jitterPx = 3)),
                    elseActions = listOf(AutomationAction.Tap(x = 90f, y = 10f, jitterPx = 3)),
                ),
            ),
            repeatMode = RepeatMode.COUNT,
        )
        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()
        assertEquals(listOf("else"), calls)
    }

    @Test
    fun andGoesToThenWhenEveryConditionMatches() = runTest {
        val calls = mutableListOf<String>()
        val runner = AutomationRunner(
            scope = this,
            executor = recordingExecutor(calls),
            conditionEvaluator = ConditionEvaluator { conditions, operator ->
                require(operator == ConditionOperator.AND)
                conditions.filterIsInstance<AutomationCondition.TextExists>()
                    .all { it.query == "yes" }
            },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.IfBlock(
                    conditions = listOf(
                        AutomationCondition.TextExists(query = "yes"),
                        AutomationCondition.TextExists(query = "yes"),
                    ),
                    operator = ConditionOperator.AND,
                    thenActions = listOf(AutomationAction.Tap(x = 10f, y = 10f, jitterPx = 3)),
                    elseActions = listOf(AutomationAction.Tap(x = 90f, y = 10f, jitterPx = 3)),
                ),
            ),
            repeatMode = RepeatMode.COUNT,
        )
        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()
        assertEquals(listOf("then"), calls)
    }

    @Test
    fun stopCancelsNestedIfExecution() = runTest {
        var evaluations = 0
        val runner = AutomationRunner(
            scope = this,
            executor = recordingExecutor(mutableListOf()),
            conditionEvaluator = ConditionEvaluator { _, _ ->
                evaluations++
                true
            },
            wait = { awaitCancellation() },
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.IfBlock(
                    thenActions = listOf(AutomationAction.Tap()),
                ),
            ),
            repeatMode = RepeatMode.INFINITE,
        )
        runner.start(config, ScreenBounds(1080, 2400))
        runCurrent()
        runner.stop()
        advanceUntilIdle()
        assertEquals(1, evaluations)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun breakLoopStopsFurtherRepeatsAndRemainingActions() = runTest {
        val calls = mutableListOf<String>()
        val executor = object : GestureExecutor {
            override suspend fun tap(point: GesturePoint): Boolean {
                calls += "tap"
                return true
            }

            override suspend fun swipe(
                start: GesturePoint,
                end: GesturePoint,
                durationMs: Long,
                stopAtEnd: Boolean,
            ) = true
        }
        val runner = AutomationRunner(
            scope = this,
            executor = executor,
            conditionEvaluator = ConditionEvaluator { _, _ -> true },
            wait = {},
            waitForLoopBoundary = { calls += "boundary" },
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Tap(),
                AutomationAction.IfBlock(
                    thenActions = listOf(AutomationAction.BreakLoop()),
                ),
                AutomationAction.Tap(),
            ),
            repeatMode = RepeatMode.INFINITE,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("tap"), calls)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun thenJumpGoesBackToEarlierAction() = runTest {
        val calls = mutableListOf<String>()
        var evaluations = 0
        val runner = AutomationRunner(
            scope = this,
            executor = object : GestureExecutor {
                override suspend fun tap(point: GesturePoint): Boolean {
                    calls += if (point.x < 50f) "start" else "then"
                    return true
                }

                override suspend fun swipe(
                    start: GesturePoint,
                    end: GesturePoint,
                    durationMs: Long,
                    stopAtEnd: Boolean,
                ) = true
            },
            conditionEvaluator = ConditionEvaluator { _, _ ->
                evaluations++
                evaluations <= 2
            },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Tap(x = 10f, y = 10f, jitterPx = 3),
                AutomationAction.IfBlock(
                    thenActions = listOf(
                        AutomationAction.Tap(x = 80f, y = 10f, jitterPx = 3),
                        AutomationAction.JumpTo(targetNumber = 1),
                    ),
                ),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("start", "then", "start", "then", "start"), calls)
        assertEquals(3, evaluations)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun jumpOutOfIfDoesNotAddIfBlockWait() = runTest {
        val waits = mutableListOf<Long>()
        val calls = mutableListOf<String>()
        val runner = AutomationRunner(
            scope = this,
            executor = object : GestureExecutor {
                override suspend fun tap(point: GesturePoint): Boolean {
                    calls += if (point.x < 50f) "start" else "after"
                    return true
                }

                override suspend fun swipe(
                    start: GesturePoint,
                    end: GesturePoint,
                    durationMs: Long,
                    stopAtEnd: Boolean,
                ) = true
            },
            conditionEvaluator = ConditionEvaluator { _, _ -> false },
            wait = { waits += it },
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Tap(x = 10f, y = 10f, jitterPx = 0, waitAfterMs = 0L, waitJitterMs = 0),
                AutomationAction.IfBlock(
                    waitAfterMs = 2_000L,
                    waitJitterMs = 0,
                    elseActions = listOf(
                        AutomationAction.JumpTo(
                            targetPath = "1",
                            maxTimes = 1,
                            waitAfterMs = 80L,
                            waitJitterMs = 0,
                        ),
                    ),
                ),
                AutomationAction.Tap(x = 400f, y = 10f, jitterPx = 0, waitAfterMs = 0L, waitJitterMs = 0),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(1, waits.count { it == 2_000L })
        assertEquals(2, waits.count { it == 80L })
        assertEquals(listOf("start", "start", "after"), calls)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun elseJumpSkipsAheadToLaterAction() = runTest {
        val calls = mutableListOf<String>()
        val runner = AutomationRunner(
            scope = this,
            executor = object : GestureExecutor {
                override suspend fun tap(point: GesturePoint): Boolean {
                    calls += when {
                        point.x < 50f -> "1"
                        point.x < 300f -> "2"
                        else -> "3"
                    }
                    return true
                }

                override suspend fun swipe(
                    start: GesturePoint,
                    end: GesturePoint,
                    durationMs: Long,
                    stopAtEnd: Boolean,
                ) = true
            },
            conditionEvaluator = ConditionEvaluator { _, _ -> false },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.IfBlock(
                    elseActions = listOf(
                        AutomationAction.Tap(x = 10f, y = 10f, jitterPx = 3),
                        AutomationAction.JumpTo(targetNumber = 3),
                    ),
                ),
                AutomationAction.Tap(x = 200f, y = 10f, jitterPx = 3),
                AutomationAction.Tap(x = 400f, y = 10f, jitterPx = 3),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("1", "3"), calls)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun waitActionPausesConfiguredSeconds() = runTest {
        val waits = mutableListOf<Long>()
        val calls = mutableListOf<String>()
        val runner = AutomationRunner(
            scope = this,
            executor = object : GestureExecutor {
                override suspend fun tap(point: GesturePoint): Boolean {
                    calls += "tap"
                    return true
                }

                override suspend fun swipe(
                    start: GesturePoint,
                    end: GesturePoint,
                    durationMs: Long,
                    stopAtEnd: Boolean,
                ) = true
            },
            randomizer = ActionRandomizer(Random(1)),
            wait = { waits += it },
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Wait(durationMs = 2_000L),
                AutomationAction.Tap(waitAfterMs = 0L, jitterPx = 3),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("tap"), calls)
        assertTrue(waits.first() in 1_970L..2_030L)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun jumpToActionMovesExecution() = runTest {
        val calls = mutableListOf<String>()
        val runner = AutomationRunner(
            scope = this,
            executor = object : GestureExecutor {
                override suspend fun tap(point: GesturePoint): Boolean {
                    calls += point.x.toInt().toString()
                    return true
                }

                override suspend fun swipe(
                    start: GesturePoint,
                    end: GesturePoint,
                    durationMs: Long,
                    stopAtEnd: Boolean,
                ) = true
            },
            conditionEvaluator = ConditionEvaluator { _, _ -> true },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Tap(x = 100f, y = 10f, jitterPx = 0),
                AutomationAction.JumpTo(targetNumber = 4),
                AutomationAction.Tap(x = 200f, y = 10f, jitterPx = 0),
                AutomationAction.Tap(x = 300f, y = 10f, jitterPx = 0),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("100", "300"), calls)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun jumpToIfMaxTimesThenRunsFollowingBranchAction() = runTest {
        val calls = mutableListOf<String>()
        val runner = AutomationRunner(
            scope = this,
            executor = object : GestureExecutor {
                override suspend fun tap(point: GesturePoint): Boolean {
                    calls += when {
                        point.x < 50f -> "start"
                        point.x < 200f -> "next"
                        else -> "after"
                    }
                    return true
                }

                override suspend fun swipe(
                    start: GesturePoint,
                    end: GesturePoint,
                    durationMs: Long,
                    stopAtEnd: Boolean,
                ) = true
            },
            conditionEvaluator = ConditionEvaluator { _, _ -> false },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Tap(x = 10f, y = 10f, jitterPx = 0),
                AutomationAction.IfBlock(
                    elseActions = listOf(
                        AutomationAction.JumpTo(targetPath = "2", maxTimes = 2),
                        AutomationAction.Tap(x = 80f, y = 10f, jitterPx = 0),
                    ),
                ),
                AutomationAction.Tap(x = 400f, y = 10f, jitterPx = 0),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("start", "next", "after"), calls)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun jumpToIfMaxTimesThenRunsFollowingJump() = runTest {
        val calls = mutableListOf<String>()
        var checks = 0
        val runner = AutomationRunner(
            scope = this,
            executor = object : GestureExecutor {
                override suspend fun tap(point: GesturePoint): Boolean {
                    calls += if (point.x < 50f) "swipe" else "after"
                    return true
                }

                override suspend fun swipe(
                    start: GesturePoint,
                    end: GesturePoint,
                    durationMs: Long,
                    stopAtEnd: Boolean,
                ) = true
            },
            conditionEvaluator = ConditionEvaluator { _, _ ->
                checks++
                checks >= 4
            },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Tap(x = 10f, y = 10f, jitterPx = 0),
                AutomationAction.IfBlock(
                    elseActions = listOf(
                        AutomationAction.JumpTo(targetPath = "2", maxTimes = 2),
                        AutomationAction.JumpTo(targetPath = "1"),
                    ),
                ),
                AutomationAction.Tap(x = 400f, y = 10f, jitterPx = 0),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("swipe", "swipe", "after"), calls)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun jumpInsideIfThenToRootRunsThatRootAction() = runTest {
        val calls = mutableListOf<String>()
        val runner = AutomationRunner(
            scope = this,
            executor = object : GestureExecutor {
                override suspend fun tap(point: GesturePoint): Boolean {
                    calls += when {
                        point.x < 50f -> "start"
                        point.x < 200f -> "sibling"
                        else -> "after"
                    }
                    return true
                }

                override suspend fun swipe(
                    start: GesturePoint,
                    end: GesturePoint,
                    durationMs: Long,
                    stopAtEnd: Boolean,
                ) = true
            },
            conditionEvaluator = ConditionEvaluator { _, _ -> true },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Tap(x = 10f, y = 10f, jitterPx = 0),
                AutomationAction.IfBlock(
                    thenActions = listOf(
                        AutomationAction.JumpTo(targetPath = "2-T2"),
                        AutomationAction.JumpTo(targetPath = "1", maxTimes = 1),
                        AutomationAction.Tap(x = 80f, y = 10f, jitterPx = 0),
                    ),
                ),
                AutomationAction.Tap(x = 400f, y = 10f, jitterPx = 0),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("start", "start", "sibling", "after"), calls)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun jumpMaxTimesStopsReturningToIf() = runTest {
        val calls = mutableListOf<String>()
        val runner = AutomationRunner(
            scope = this,
            executor = object : GestureExecutor {
                override suspend fun tap(point: GesturePoint): Boolean {
                    calls += when {
                        point.x < 50f -> "start"
                        point.x < 200f -> "then"
                        else -> "after"
                    }
                    return true
                }

                override suspend fun swipe(
                    start: GesturePoint,
                    end: GesturePoint,
                    durationMs: Long,
                    stopAtEnd: Boolean,
                ) = true
            },
            conditionEvaluator = ConditionEvaluator { _, _ -> true },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Tap(x = 10f, y = 10f, jitterPx = 0),
                AutomationAction.IfBlock(
                    thenActions = listOf(
                        AutomationAction.Tap(x = 80f, y = 10f, jitterPx = 0),
                        AutomationAction.JumpTo(targetNumber = 1, maxTimes = 2),
                    ),
                ),
                AutomationAction.Tap(x = 400f, y = 10f, jitterPx = 0),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("start", "then", "start", "then", "start", "then", "after"), calls)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun jumpToBranchPathExecutesNestedAction() = runTest {
        val calls = mutableListOf<String>()
        val runner = AutomationRunner(
            scope = this,
            executor = object : GestureExecutor {
                override suspend fun tap(point: GesturePoint): Boolean {
                    calls += when {
                        point.x < 50f -> "root"
                        point.x < 150f -> "then1"
                        else -> "then2"
                    }
                    return true
                }

                override suspend fun swipe(
                    start: GesturePoint,
                    end: GesturePoint,
                    durationMs: Long,
                    stopAtEnd: Boolean,
                ) = true
            },
            conditionEvaluator = ConditionEvaluator { _, _ -> true },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Tap(x = 10f, y = 10f, jitterPx = 0),
                AutomationAction.IfBlock(
                    thenActions = listOf(
                        AutomationAction.JumpTo(targetPath = "2-T3"),
                        AutomationAction.Tap(x = 80f, y = 10f, jitterPx = 0),
                        AutomationAction.Tap(x = 180f, y = 10f, jitterPx = 0),
                    ),
                ),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("root", "then2"), calls)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    @Test
    fun invalidJumpContinuesToNextAction() = runTest {
        val calls = mutableListOf<String>()
        val runner = AutomationRunner(
            scope = this,
            executor = object : GestureExecutor {
                override suspend fun tap(point: GesturePoint): Boolean {
                    calls += "tap"
                    return true
                }

                override suspend fun swipe(
                    start: GesturePoint,
                    end: GesturePoint,
                    durationMs: Long,
                    stopAtEnd: Boolean,
                ) = true
            },
            conditionEvaluator = ConditionEvaluator { _, _ -> true },
            wait = {},
            waitForLoopBoundary = {},
        )
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.JumpTo(targetNumber = 99),
                AutomationAction.Tap(jitterPx = 3),
            ),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 1,
        )

        runner.start(config, ScreenBounds(1080, 2400))
        advanceUntilIdle()

        assertEquals(listOf("tap"), calls)
        assertEquals(RunnerState.IDLE, runner.state.value)
    }

    private fun recordingExecutor(calls: MutableList<String>) = object : GestureExecutor {
        override suspend fun tap(point: GesturePoint): Boolean {
            calls += if (point.x < 50f) "then" else "else"
            return true
        }

        override suspend fun swipe(
            start: GesturePoint,
            end: GesturePoint,
            durationMs: Long,
            stopAtEnd: Boolean,
        ) = true
    }
}
