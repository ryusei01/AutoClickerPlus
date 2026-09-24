package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.TextMatchMode
import com.autoclickerplus.model.hasRegion
import kotlin.math.abs

data class UiNodeSnapshot(
    val values: List<String>,
    val enabled: Boolean,
    val clickable: Boolean,
    val left: Int = Int.MIN_VALUE,
    val top: Int = Int.MIN_VALUE,
    val right: Int = Int.MIN_VALUE,
    val bottom: Int = Int.MIN_VALUE,
) {
    val hasBounds: Boolean
        get() = left != Int.MIN_VALUE
}

object ConditionLogic {
    fun textExists(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.TextExists,
    ): Boolean = nodes.any { node ->
        nodeMatchesRegion(node, condition) &&
            node.values.any { matches(it, condition.query, condition.matchMode) }
    }

    fun uiStateMatches(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.UiState,
    ): Boolean = nodes.any { node ->
        nodeMatchesRegion(node, condition) &&
            node.values.any { matches(it, condition.query, condition.matchMode) } &&
            (condition.expectedEnabled == null ||
                node.enabled == condition.expectedEnabled) &&
            (condition.expectedClickable == null ||
                node.clickable == condition.expectedClickable)
    }

    private fun nodeMatchesRegion(
        node: UiNodeSnapshot,
        condition: AutomationCondition.TextExists,
    ): Boolean = nodeMatchesRegion(
        node,
        condition.hasRegion(),
        condition.regionLeft,
        condition.regionTop,
        condition.regionRight,
        condition.regionBottom,
    )

    private fun nodeMatchesRegion(
        node: UiNodeSnapshot,
        condition: AutomationCondition.UiState,
    ): Boolean = nodeMatchesRegion(
        node,
        condition.hasRegion(),
        condition.regionLeft,
        condition.regionTop,
        condition.regionRight,
        condition.regionBottom,
    )

    private fun nodeMatchesRegion(
        node: UiNodeSnapshot,
        hasRegion: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        if (!hasRegion) return true
        if (!node.hasBounds) return false
        return node.left < right && node.right > left && node.top < bottom && node.bottom > top
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

    private fun channel(color: Int, shift: Int): Int = color shr shift and 0xFF
}
