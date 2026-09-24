package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.ConditionRegion
import com.autoclickerplus.model.TextMatchMode
import kotlin.math.abs

data class UiNodeSnapshot(
    val values: List<String>,
    val enabled: Boolean,
    val clickable: Boolean,
    val normalizedCenterX: Float? = null,
    val normalizedCenterY: Float? = null,
)

object ConditionLogic {
    fun textExists(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.TextExists,
    ): Boolean = nodes.any { node ->
        regionMatches(node, condition.region) &&
        node.values.any { matches(it, condition.query, condition.matchMode) }
    }

    fun uiStateMatches(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.UiState,
    ): Boolean = nodes.any { node ->
        regionMatches(node, condition.region) &&
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

    private fun regionMatches(node: UiNodeSnapshot, region: ConditionRegion): Boolean {
        if (region == ConditionRegion.ANY) return true
        val x = node.normalizedCenterX ?: return false
        val y = node.normalizedCenterY ?: return false
        val target = region.targetCell() ?: return true
        return gridIndex(y) == target.row && gridIndex(x) == target.column
    }

    private fun gridIndex(value: Float): Int = when {
        value < ONE_THIRD -> 0
        value < TWO_THIRDS -> 1
        else -> 2
    }

    private fun ConditionRegion.targetCell(): GridCell? = when (this) {
        ConditionRegion.ANY -> null
        ConditionRegion.TOP_LEFT -> GridCell(0, 0)
        ConditionRegion.TOP_CENTER -> GridCell(0, 1)
        ConditionRegion.TOP_RIGHT -> GridCell(0, 2)
        ConditionRegion.MIDDLE_LEFT -> GridCell(1, 0)
        ConditionRegion.CENTER -> GridCell(1, 1)
        ConditionRegion.MIDDLE_RIGHT -> GridCell(1, 2)
        ConditionRegion.BOTTOM_LEFT -> GridCell(2, 0)
        ConditionRegion.BOTTOM_CENTER -> GridCell(2, 1)
        ConditionRegion.BOTTOM_RIGHT -> GridCell(2, 2)
    }

    private data class GridCell(val row: Int, val column: Int)

    private fun channel(color: Int, shift: Int): Int = color shr shift and 0xFF

    private companion object {
        const val ONE_THIRD = 1f / 3f
        const val TWO_THIRDS = 2f / 3f
    }
}
