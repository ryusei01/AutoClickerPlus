package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.RepeatMode
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
    suspend fun swipe(start: GesturePoint, end: GesturePoint, durationMs: Long): Boolean
}

enum class RunnerState {
    IDLE,
    RUNNING,
    FAILED,
}

class ActionRandomizer(private val random: Random = Random.Default) {
    fun waitMs(baseMs: Long): Long =
        (baseMs + random.nextInt(-TIME_JITTER_MS, TIME_JITTER_MS + 1)).coerceAtLeast(0L)

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
        const val TIME_JITTER_MS = 30
    }
}

class AutomationRunner(
    private val scope: CoroutineScope,
    private val executor: GestureExecutor,
    private val randomizer: ActionRandomizer = ActionRandomizer(),
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutableState = MutableStateFlow(RunnerState.IDLE)
    val state: StateFlow<RunnerState> = mutableState.asStateFlow()

    private var job: Job? = null

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
            for (action in config.actions) {
                val succeeded = when (action) {
                    is AutomationAction.Tap -> executor.tap(randomizer.tapPoint(action, bounds))
                    is AutomationAction.Swipe -> {
                        val (start, end) = randomizer.swipePoints(action, bounds)
                        executor.swipe(start, end, action.durationMs)
                    }
                }
                if (!succeeded) throw GestureFailedException()
                wait(randomizer.waitMs(action.waitAfterMs))
            }
            loop++
        }
    }

    private class GestureFailedException : RuntimeException()
}
