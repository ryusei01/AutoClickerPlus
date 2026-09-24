package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.ScreenRegion
import com.autoclickerplus.model.TextMatchMode
import com.autoclickerplus.model.containsNodeCenter
import kotlin.math.abs

data class UiNodeSnapshot(
    val values: List<String>,
    val enabled: Boolean,
    val clickable: Boolean,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val visible: Boolean = true,
    val parentIndex: Int = -1,
)

object ConditionLogic {
    fun textExists(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.TextExists,
    ): Boolean = nodes.any { node ->
        node.visible &&
            node.matchesRegion(condition.region) &&
            node.values.any { matches(it, condition.query, condition.matchMode) }
    }

    fun uiStateMatches(
        nodes: List<UiNodeSnapshot>,
        condition: AutomationCondition.UiState,
    ): Boolean = nodes.indices.any { index ->
        val node = nodes[index]
        node.visible &&
            node.matchesRegion(condition.region) &&
            node.values.any { matches(it, condition.query, condition.matchMode) } &&
            (condition.expectedEnabled == null ||
                effectiveEnabled(nodes, index) == condition.expectedEnabled) &&
            (condition.expectedClickable == null ||
                effectiveClickable(nodes, index, condition) == condition.expectedClickable)
    }

    fun indicatesDisabledState(stateDescription: String?): Boolean {
        if (stateDescription.isNullOrBlank()) return false
        return DISABLED_STATE_MARKERS.any { marker ->
            stateDescription.contains(marker, ignoreCase = true)
        }
    }

    fun colorsMatch(actual: Int, expected: Int, tolerance: Int): Boolean =
        abs(channel(actual, 16) - channel(expected, 16)) <= tolerance &&
            abs(channel(actual, 8) - channel(expected, 8)) <= tolerance &&
            abs(channel(actual, 0) - channel(expected, 0)) <= tolerance

    private fun effectiveEnabled(nodes: List<UiNodeSnapshot>, start: Int): Boolean {
        var index = start
        val seen = HashSet<Int>()
        while (index in nodes.indices && seen.add(index)) {
            if (!nodes[index].enabled) return false
            index = nodes[index].parentIndex
        }
        return true
    }

    private fun effectiveClickable(
        nodes: List<UiNodeSnapshot>,
        start: Int,
        condition: AutomationCondition.UiState,
    ): Boolean {
        var index = start
        val seen = HashSet<Int>()
        var first = true
        while (index in nodes.indices && seen.add(index)) {
            val node = nodes[index]
            if (!first &&
                node.values.any { it.isNotBlank() } &&
                node.values.none { matches(it, condition.query, condition.matchMode) }
            ) {
                return false
            }
            if (node.clickable) return true
            index = node.parentIndex
            first = false
        }
        return false
    }

    private fun UiNodeSnapshot.matchesRegion(region: ScreenRegion?): Boolean {
        if (region == null) return true
        return region.containsNodeCenter(left, top, right, bottom)
    }

    private fun matches(value: String, query: String, mode: TextMatchMode): Boolean {
        if (query.isBlank()) return false
        return when (mode) {
            TextMatchMode.EXACT -> value.equals(query, ignoreCase = true)
            TextMatchMode.CONTAINS -> value.contains(query, ignoreCase = true)
        }
    }

    private fun channel(color: Int, shift: Int): Int = color shr shift and 0xFF

    private val DISABLED_STATE_MARKERS = listOf("disabled", "無効", "使用不可")
}
