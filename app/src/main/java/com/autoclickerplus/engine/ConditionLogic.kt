package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.ScreenRegion
import com.autoclickerplus.model.TextMatchMode
import kotlin.math.abs

data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class UiNodeSnapshot(
    val values: List<String>,
    val enabled: Boolean,
    val clickable: Boolean,
    val bounds: UiBounds? = null,
)

object ConditionLogic {
    fun textExists(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.TextExists,
    ): Boolean = nodes.any { node ->
        node.isIn(condition.region) &&
            node.values.any { matches(it, condition.query, condition.matchMode) }
    }

    fun uiStateMatches(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.UiState,
    ): Boolean = nodes.any { node ->
        node.isIn(condition.region) &&
            node.values.any { matches(it, condition.query, condition.matchMode) } &&
            (condition.expectedEnabled == null ||
                node.enabled == condition.expectedEnabled) &&
            (condition.expectedClickable == null ||
                node.clickable == condition.expectedClickable)
    }

    fun colorsMatch(actual: Int, expected: Int, tolerance: Int): Boolean =
        abs(channel(actual, 16) - channel(expected, 16)) <= tolerance &&
            abs(channel(actual, 8) - channel(expected, 8)) <= tolerance &&
            abs(channel(actual, 0) - channel(expected, 0)) <= tolerance

    private fun matches(value: String, query: String, mode: TextMatchMode): Boolean {
        if (query.isBlank()) return false
        return when (mode) {
            TextMatchMode.EXACT -> value.equals(query, ignoreCase = true)
            TextMatchMode.CONTAINS -> value.contains(query, ignoreCase = true)
        }
    }

    private fun UiNodeSnapshot.isIn(region: ScreenRegion?): Boolean {
        if (region == null) return true
        val nodeBounds = bounds ?: return false
        val centerX = nodeBounds.left + (nodeBounds.right - nodeBounds.left) / 2
        val centerY = nodeBounds.top + (nodeBounds.bottom - nodeBounds.top) / 2
        return centerX in region.left..region.right &&
            centerY in region.top..region.bottom
    }

    private fun channel(color: Int, shift: Int): Int = color shr shift and 0xFF
}
