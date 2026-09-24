package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.ConditionOperator
import com.autoclickerplus.model.RepeatMode
import com.autoclickerplus.model.resolveJumpTarget
import com.autoclickerplus.model.resolvedTargetPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class ScreenBounds(val width: Int, val height: Int)
data class GesturePoint(val x: Float, val y: Float)

interface GestureExecutor {
    suspend fun tap(point: GesturePoint): Boolean
    suspend fun swipe(
        start: GesturePoint,
        end: GesturePoint,
        durationMs: Long,
        stopAtEnd: Boolean = true,
    ): Boolean

    suspend fun scrollToEnd(start: GesturePoint, end: GesturePoint): Boolean = true
}

fun interface ConditionEvaluator {
    suspend fun evaluate(
        conditions: List<AutomationCondition>,
        operator: ConditionOperator,
    ): Boolean
}

class ConditionEvaluationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

enum class RunnerState {
    IDLE,
    RUNNING,
    FAILED,
}

class ActionRandomizer(private val random: Random = Random.Default) {
    fun waitMs(baseMs: Long, jitterMs: Int = DEFAULT_TIME_JITTER_MS): Long {
        val base = baseMs.coerceAtLeast(0L)
        if (base == 0L) return 0L
        val jitter = jitterMs.coerceAtLeast(0)
        if (jitter == 0) return base
        return (base + random.nextInt(-jitter, jitter + 1)).coerceAtLeast(0L)
    }

    fun tapPoint(action: AutomationAction.Tap, bounds: ScreenBounds): GesturePoint {
        val radius = sqrt(random.nextDouble()) * action.jitterPx
        val angle = random.nextDouble() * 2.0 * PI
        return GesturePoint(
            x = (action.x + cos(angle).toFloat() * radius.toFloat()).coerceIn(0f, bounds.maxX),
            y = (action.y + sin(angle).toFloat() * radius.toFloat()).coerceIn(0f, bounds.maxY),
        )
    }

    fun swipePoints(
        action: AutomationAction.Swipe,
        bounds: ScreenBounds,
    ): Pair<GesturePoint, GesturePoint> {
        val jitter = action.jitterPx.toFloat()
        val dx = safeOffset(action.startX, action.endX, bounds.maxX, jitter)
        val dy = safeOffset(action.startY, action.endY, bounds.maxY, jitter)
        return GesturePoint(
            (action.startX + dx).coerceIn(0f, bounds.maxX),
            (action.startY + dy).coerceIn(0f, bounds.maxY),
        ) to GesturePoint(
            (action.endX + dx).coerceIn(0f, bounds.maxX),
            (action.endY + dy).coerceIn(0f, bounds.maxY),
        )
    }

    private fun safeOffset(first: Float, second: Float, screenMax: Float, jitter: Float): Float {
        val requiredMin = -minOf(first, second)
        val requiredMax = screenMax - maxOf(first, second)
        if (requiredMin > requiredMax) return 0f
        val jitterMin = maxOf(-jitter, requiredMin)
        val jitterMax = minOf(jitter, requiredMax)
        return if (jitterMin <= jitterMax) {
            randomBetween(jitterMin, jitterMax)
        } else {
            0f.coerceIn(requiredMin, requiredMax)
        }
    }

    private fun randomBetween(minimum: Float, maximum: Float): Float {
        if (maximum <= minimum) return minimum
        return minimum + random.nextFloat() * (maximum - minimum)
    }

    private val ScreenBounds.maxX: Float get() = (width - 1).coerceAtLeast(0).toFloat()
    private val ScreenBounds.maxY: Float get() = (height - 1).coerceAtLeast(0).toFloat()

    private companion object {
        const val DEFAULT_TIME_JITTER_MS = 30
    }
}

class AutomationRunner(
    private val scope: CoroutineScope,
    private val executor: GestureExecutor,
    private val conditionEvaluator: ConditionEvaluator = ConditionEvaluator { _, _ ->
        throw ConditionEvaluationException("条件評価機能が接続されていません")
    },
    private val randomizer: ActionRandomizer = ActionRandomizer(),
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val waitForLoopBoundary: suspend () -> Unit = { delay(LOOP_BOUNDARY_MS) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutableState = MutableStateFlow(RunnerState.IDLE)
    val state: StateFlow<RunnerState> = mutableState.asStateFlow()

    private var job: Job? = null
    private val jumpUseCounts = mutableMapOf<String, Int>()

    @Synchronized
    fun start(config: AutomationConfig, bounds: ScreenBounds): Boolean {
        if (job?.isActive == true || config.actions.isEmpty()) return false
        job = scope.launch {
            mutableState.value = RunnerState.RUNNING
            try {
                runActions(config, bounds)
                mutableState.value = RunnerState.IDLE
            } catch (cancelled: CancellationException) {
                mutableState.value = RunnerState.IDLE
                throw cancelled
            } catch (_: GestureFailedException) {
                mutableState.value = RunnerState.FAILED
            } catch (_: ConditionEvaluationException) {
                mutableState.value = RunnerState.FAILED
            } catch (_: LoopBreakException) {
                mutableState.value = RunnerState.IDLE
            } catch (_: JumpLimitException) {
                mutableState.value = RunnerState.FAILED
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        mutableState.value = RunnerState.IDLE
    }

    private suspend fun runActions(config: AutomationConfig, bounds: ScreenBounds) {
        val totalLoops = if (config.repeatMode == RepeatMode.INFINITE) Int.MAX_VALUE else config.repeatCount
        var loop = 0
        while (loop < totalLoops) {
            jumpUseCounts.clear()
            try {
                executeActions(
                    actions = config.actions,
                    rootActions = config.actions,
                    bounds = bounds,
                    allowJump = true,
                )
            } catch (_: LoopBreakException) {
                return
            }
            loop++
            if (loop < totalLoops) {
                waitForLoopBoundary()
            }
        }
    }

    private suspend fun executeActions(
        actions: List<AutomationAction>,
        rootActions: List<AutomationAction>,
        bounds: ScreenBounds,
        allowJump: Boolean,
        startIndex: Int = 0,
    ): BranchOutcome {
        var currentActions = actions
        var index = startIndex
        var jumps = 0
        while (index < currentActions.size) {
            val action = currentActions[index]
            val outcome = executeAction(action, rootActions, bounds)
            val waitAfterJump = outcome is BranchOutcome.Jump && action is AutomationAction.JumpTo
            if (outcome is BranchOutcome.Continue || waitAfterJump) {
                wait(randomizer.waitMs(action.waitAfterMs, action.waitJitterMs))
            }
            when (outcome) {
                BranchOutcome.Continue -> index++
                is BranchOutcome.Jump -> {
                    if (!allowJump) return outcome
                    val target = resolveJumpTarget(rootActions, outcome.targetPath)
                    if (target == null) {
                        index++
                        continue
                    }
                    jumps++
                    if (jumps > MAX_JUMPS_PER_LOOP) {
                        throw JumpLimitException()
                    }
                    if (target.actions === currentActions) {
                        index = target.index
                        continue
                    }
                    if (target.actions === rootActions) {
                        currentActions = rootActions
                        index = target.index
                        continue
                    }
                    var nestedOutcome = executeActions(
                        actions = target.actions,
                        rootActions = rootActions,
                        bounds = bounds,
                        allowJump = false,
                        startIndex = target.index,
                    )
                    while (nestedOutcome is BranchOutcome.Jump) {
                        val next = resolveJumpTarget(rootActions, nestedOutcome.targetPath) ?: break
                        jumps++
                        if (jumps > MAX_JUMPS_PER_LOOP) {
                            throw JumpLimitException()
                        }
                        nestedOutcome = if (next.actions === rootActions) {
                            currentActions = rootActions
                            index = next.index
                            BranchOutcome.Continue
                        } else {
                            executeActions(
                                actions = next.actions,
                                rootActions = rootActions,
                                bounds = bounds,
                                allowJump = false,
                                startIndex = next.index,
                            )
                        }
                    }
                    if (nestedOutcome is BranchOutcome.Jump) {
                        return nestedOutcome
                    }
                    currentActions = rootActions
                    index = target.rootIndexAfter
                }
            }
        }
        return BranchOutcome.Continue
    }

    private suspend fun executeAction(
        action: AutomationAction,
        rootActions: List<AutomationAction>,
        bounds: ScreenBounds,
    ): BranchOutcome {
        when (action) {
            is AutomationAction.Tap -> {
                if (!executor.tap(randomizer.tapPoint(action, bounds))) {
                    throw GestureFailedException()
                }
            }
            is AutomationAction.Swipe -> {
                val (start, end) = randomizer.swipePoints(action, bounds)
                if (action.fullScroll) {
                    if (!executor.scrollToEnd(start, end)) {
                        throw GestureFailedException()
                    }
                    wait(SWIPE_SETTLE_MS)
                } else {
                    val startedAt = nowMs()
                    if (!executor.swipe(start, end, action.durationMs, action.stopAtEnd)) {
                        throw GestureFailedException()
                    }
                    val elapsed = (nowMs() - startedAt).coerceAtLeast(0L)
                    val expectedMs = action.durationMs +
                        if (action.stopAtEnd) SWIPE_HOLD_MS else 0L
                    val remainingStroke = (expectedMs - elapsed).coerceAtLeast(0L)
                    wait(remainingStroke + SWIPE_SETTLE_MS)
                }
            }
            is AutomationAction.IfBlock -> {
                val matched = conditionEvaluator.evaluate(action.conditions, action.operator)
                val branchOutcome = executeActions(
                    actions = if (matched) action.thenActions else action.elseActions,
                    rootActions = rootActions,
                    bounds = bounds,
                    allowJump = false,
                )
                if (branchOutcome is BranchOutcome.Jump) {
                    return branchOutcome
                }
            }
            is AutomationAction.BreakLoop -> throw LoopBreakException()
            is AutomationAction.Wait -> wait(randomizer.waitMs(action.durationMs, action.waitJitterMs))
            is AutomationAction.JumpTo -> {
                if (action.maxTimes > 0) {
                    val used = jumpUseCounts[action.id] ?: 0
                    if (used >= action.maxTimes) {
                        jumpUseCounts.remove(action.id)
                        return BranchOutcome.Continue
                    }
                    jumpUseCounts[action.id] = used + 1
                }
                return BranchOutcome.Jump(action.resolvedTargetPath())
            }
        }
        return BranchOutcome.Continue
    }

    private sealed class BranchOutcome {
        data object Continue : BranchOutcome()
        data class Jump(val targetPath: String) : BranchOutcome()
    }

    private class GestureFailedException : RuntimeException()
    private class LoopBreakException : RuntimeException()
    private class JumpLimitException : RuntimeException()

    private companion object {
        const val LOOP_BOUNDARY_MS = 16L
        const val SWIPE_SETTLE_MS = 80L
        const val SWIPE_HOLD_MS = 180L
        const val MAX_JUMPS_PER_LOOP = 10_000
    }
}
