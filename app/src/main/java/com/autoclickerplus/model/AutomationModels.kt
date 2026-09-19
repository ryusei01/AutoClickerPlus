package com.autoclickerplus.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
sealed class AutomationAction {
    abstract val id: String
    abstract val waitAfterMs: Long
    abstract val jitterPx: Int

    @Serializable
    @SerialName("tap")
    data class Tap(
        override val id: String = UUID.randomUUID().toString(),
        val x: Float = 540f,
        val y: Float = 1000f,
        override val waitAfterMs: Long = 500L,
        override val jitterPx: Int = 5,
    ) : AutomationAction()

    @Serializable
    @SerialName("swipe")
    data class Swipe(
        override val id: String = UUID.randomUUID().toString(),
        val startX: Float = 540f,
        val startY: Float = 1500f,
        val endX: Float = 540f,
        val endY: Float = 700f,
        val durationMs: Long = 300L,
        override val waitAfterMs: Long = 300L,
        override val jitterPx: Int = 5,
    ) : AutomationAction()
}

@Serializable
data class AutomationConfig(
    val actions: List<AutomationAction> = emptyList(),
    val repeatMode: RepeatMode = RepeatMode.INFINITE,
    val repeatCount: Int = 1,
)

@Serializable
enum class RepeatMode {
    INFINITE,
    COUNT,
}

fun AutomationAction.normalized(): AutomationAction = when (this) {
    is AutomationAction.Tap -> copy(
        x = x.coerceAtLeast(0f),
        y = y.coerceAtLeast(0f),
        waitAfterMs = waitAfterMs.coerceAtLeast(0L),
        jitterPx = jitterPx.coerceIn(3, 10),
    )
    is AutomationAction.Swipe -> copy(
        startX = startX.coerceAtLeast(0f),
        startY = startY.coerceAtLeast(0f),
        endX = endX.coerceAtLeast(0f),
        endY = endY.coerceAtLeast(0f),
        durationMs = durationMs.coerceIn(100L, 2_000L),
        waitAfterMs = waitAfterMs.coerceAtLeast(0L),
        jitterPx = jitterPx.coerceIn(3, 10),
    )
}

fun AutomationConfig.normalized(): AutomationConfig = copy(
    actions = actions.map(AutomationAction::normalized),
    repeatCount = repeatCount.coerceIn(1, 100_000),
)
