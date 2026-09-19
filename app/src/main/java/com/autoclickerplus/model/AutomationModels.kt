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
        val stopAtEnd: Boolean = true,
        override val waitAfterMs: Long = 300L,
        override val jitterPx: Int = 5,
    ) : AutomationAction()

    @Serializable
    @SerialName("if")
    data class IfBlock(
        override val id: String = UUID.randomUUID().toString(),
        val conditions: List<AutomationCondition> = listOf(AutomationCondition.TextExists()),
        val operator: ConditionOperator = ConditionOperator.AND,
        val thenActions: List<AutomationAction> = emptyList(),
        val elseActions: List<AutomationAction> = emptyList(),
        override val waitAfterMs: Long = 0L,
        override val jitterPx: Int = 3,
    ) : AutomationAction()
}

@Serializable
sealed class AutomationCondition {
    abstract val id: String

    @Serializable
    @SerialName("text_exists")
    data class TextExists(
        override val id: String = UUID.randomUUID().toString(),
        val query: String = "",
        val matchMode: TextMatchMode = TextMatchMode.CONTAINS,
    ) : AutomationCondition()

    @Serializable
    @SerialName("ui_state")
    data class UiState(
        override val id: String = UUID.randomUUID().toString(),
        val query: String = "",
        val matchMode: TextMatchMode = TextMatchMode.CONTAINS,
        val expectedEnabled: Boolean? = true,
        val expectedClickable: Boolean? = null,
    ) : AutomationCondition()

    @Serializable
    @SerialName("pixel_color")
    data class PixelColor(
        override val id: String = UUID.randomUUID().toString(),
        val x: Int = 0,
        val y: Int = 0,
        val argb: Int = 0xFFFFFFFF.toInt(),
        val tolerance: Int = 20,
    ) : AutomationCondition()
}

@Serializable
enum class ConditionOperator {
    AND,
    OR,
}

@Serializable
enum class TextMatchMode {
    EXACT,
    CONTAINS,
}

@Serializable
enum class BranchSide {
    THEN,
    ELSE,
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

@Serializable
data class AutomationScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新しいスクリプト",
    val config: AutomationConfig = AutomationConfig(),
)

@Serializable
data class ScriptLibrary(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val activeScriptId: String,
    val scripts: List<AutomationScript>,
) {
    val activeScript: AutomationScript
        get() = scripts.firstOrNull { it.id == activeScriptId }
            ?: scripts.first()

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2

        fun default(): ScriptLibrary {
            val script = AutomationScript(name = "デフォルト")
            return ScriptLibrary(
                activeScriptId = script.id,
                scripts = listOf(script),
            )
        }

        fun fromLegacy(config: AutomationConfig): ScriptLibrary {
            val script = AutomationScript(
                name = "デフォルト",
                config = config.normalized(),
            )
            return ScriptLibrary(
                activeScriptId = script.id,
                scripts = listOf(script),
            )
        }
    }
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
    is AutomationAction.IfBlock -> copy(
        conditions = conditions.ifEmpty { listOf(AutomationCondition.TextExists()) }
            .map(AutomationCondition::normalized),
        thenActions = thenActions.map(AutomationAction::normalized),
        elseActions = elseActions.map(AutomationAction::normalized),
        waitAfterMs = waitAfterMs.coerceAtLeast(0L),
        jitterPx = 3,
    )
}

fun AutomationConfig.normalized(): AutomationConfig = copy(
    actions = actions.map(AutomationAction::normalized),
    repeatCount = repeatCount.coerceIn(1, 100_000),
)

fun AutomationCondition.normalized(): AutomationCondition = when (this) {
    is AutomationCondition.TextExists -> copy(query = query.take(200))
    is AutomationCondition.UiState -> copy(query = query.take(200))
    is AutomationCondition.PixelColor -> copy(
        x = x.coerceAtLeast(0),
        y = y.coerceAtLeast(0),
        tolerance = tolerance.coerceIn(0, 255),
    )
}

fun ScriptLibrary.normalized(): ScriptLibrary {
    val normalizedScripts = scripts.ifEmpty { ScriptLibrary.default().scripts }.map { script ->
        script.copy(
            name = script.name.trim().ifEmpty { "名称未設定" }.take(80),
            config = script.config.normalized(),
        )
    }
    val selectedId = activeScriptId.takeIf { id -> normalizedScripts.any { it.id == id } }
        ?: normalizedScripts.first().id
    return copy(
        schemaVersion = ScriptLibrary.CURRENT_SCHEMA_VERSION,
        activeScriptId = selectedId,
        scripts = normalizedScripts,
    )
}
