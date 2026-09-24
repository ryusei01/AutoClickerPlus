package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.TextMatchMode
import kotlin.math.abs

data class UiNodeSnapshot(
    val values: List<String>,
    val enabled: Boolean,
    val clickable: Boolean,
    val screenLeft: Int = 0,
    val screenTop: Int = 0,
    val screenRight: Int = 0,
    val screenBottom: Int = 0,
)

object ConditionLogic {
    fun textExists(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.TextExists,
    ): Boolean = nodes.any { node ->
        node.values.any { matches(it, condition.query, condition.matchMode) } &&
            overlapsRegion(node, condition.regionLeft, condition.regionTop, condition.regionRight, condition.regionBottom)
    }

    fun uiStateMatches(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.UiState,
    ): Boolean = nodes.any { node ->
        node.values.any { matches(it, condition.query, condition.matchMode) } &&
            (condition.expectedEnabled == null ||
                node.enabled == condition.expectedEnabled) &&
            (condition.expectedClickable == null ||
                node.clickable == condition.expectedClickable) &&
            overlapsRegion(node, condition.regionLeft, condition.regionTop, condition.regionRight, condition.regionBottom)
    }

    fun colorsMatch(actual: Int, expected: Int, tolerance: Int): Boolean =
        abs(channel(actual, 16) - channel(expected, 16)) <= tolerance &&
            abs(channel(actual, 8) - channel(expected, 8)) <= tolerance &&
            abs(channel(actual, 0) - channel(expected, 0)) <= tolerance

    fun overlapsRegion(
        node: UiNodeSnapshot,
        left: Int?,
        top: Int?,
        right: Int?,
        bottom: Int?,
    ): Boolean {
        if (left == null && top == null && right == null && bottom == null) return true
        if (left != null && node.screenRight <= left) return false
        if (top != null && node.screenBottom <= top) return false
        if (right != null && node.screenLeft >= right) return false
        if (bottom != null && node.screenTop >= bottom) return false
        return true
    }

    private fun matches(value: String, query: String, mode: TextMatchMode): Boolean {
        if (query.isBlank()) return false
        return when (mode) {
            TextMatchMode.EXACT -> value.equals(query, ignoreCase = true)
            TextMatchMode.CONTAINS -> value.contains(query, ignoreCase = true)
        }
    }

    private fun channel(color: Int, shift: Int): Int = color shr shift and 0xFF
}
