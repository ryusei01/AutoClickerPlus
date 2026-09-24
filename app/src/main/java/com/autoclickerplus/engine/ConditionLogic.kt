package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.TextMatchMode
import com.autoclickerplus.model.normalized
import kotlin.math.abs

data class UiNodeSnapshot(
    val values: List<String>,
    val enabled: Boolean,
    val clickable: Boolean,
    val bounds: AutomationCondition.ScreenRegion? = null,
)

object ConditionLogic {
    fun textExists(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.TextExists,
    ): Boolean = nodes.any { node ->
        inRegion(node, condition.region) &&
            node.values.any { matches(it, condition.query, condition.matchMode) }
    }

    fun uiStateMatches(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.UiState,
    ): Boolean = nodes.any { node ->
        inRegion(node, condition.region) &&
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

    private fun inRegion(
        node: UiNodeSnapshot,
        region: AutomationCondition.ScreenRegion?,
    ): Boolean {
        if (region == null) return true
        val bounds = node.bounds?.normalized() ?: return false
        val target = region.normalized()
        val centerX = bounds.left + (bounds.right - bounds.left) / 2
        val centerY = bounds.top + (bounds.bottom - bounds.top) / 2
        return centerX in target.left..target.right &&
            centerY in target.top..target.bottom
    }

    private fun channel(color: Int, shift: Int): Int = color shr shift and 0xFF
}
