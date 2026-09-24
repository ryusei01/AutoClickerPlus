package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.ConditionRegion
import com.autoclickerplus.model.TextMatchMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionLogicTest {
    private val nodes = listOf(
        UiNodeSnapshot(
            values = listOf("購入する", "購入ボタン"),
            enabled = true,
            clickable = true,
            normalizedCenterX = 0.2f,
            normalizedCenterY = 0.2f,
        ),
        UiNodeSnapshot(
            values = listOf("準備中"),
            enabled = false,
            clickable = false,
            normalizedCenterX = 0.8f,
            normalizedCenterY = 0.8f,
        ),
    )

    @Test
    fun matchesTextWithExactAndContainsModes() {
        assertTrue(
            ConditionLogic.textExists(
                nodes,
                AutomationCondition.TextExists(
                    query = "購入",
                    matchMode = TextMatchMode.CONTAINS,
                ),
            ),
        )
        assertFalse(
            ConditionLogic.textExists(
                nodes,
                AutomationCondition.TextExists(
                    query = "購入",
                    matchMode = TextMatchMode.EXACT,
                ),
            ),
        )
    }

    @Test
    fun matchesEnabledAndClickableStateOnSameNode() {
        assertTrue(
            ConditionLogic.uiStateMatches(
                nodes,
                AutomationCondition.UiState(
                    query = "購入する",
                    expectedEnabled = true,
                    expectedClickable = true,
                ),
            ),
        )
        assertFalse(
            ConditionLogic.uiStateMatches(
                nodes,
                AutomationCondition.UiState(
                    query = "準備中",
                    expectedEnabled = true,
                ),
            ),
        )
    }

    @Test
    fun comparesRgbChannelsUsingTolerance() {
        assertTrue(ConditionLogic.colorsMatch(0xFF102030.toInt(), 0xFF122331.toInt(), 3))
        assertFalse(ConditionLogic.colorsMatch(0xFF102030.toInt(), 0xFF202030.toInt(), 3))
    }

    @Test
    fun matchesSameTextByDifferentRegion() {
        val duplicatedTextNodes = listOf(
            UiNodeSnapshot(
                values = listOf("購入"),
                enabled = true,
                clickable = true,
                normalizedCenterX = 0.15f,
                normalizedCenterY = 0.15f,
            ),
            UiNodeSnapshot(
                values = listOf("購入"),
                enabled = true,
                clickable = true,
                normalizedCenterX = 0.85f,
                normalizedCenterY = 0.15f,
            ),
        )

        assertTrue(
            ConditionLogic.textExists(
                duplicatedTextNodes,
                AutomationCondition.TextExists(
                    query = "購入",
                    matchMode = TextMatchMode.EXACT,
                    region = ConditionRegion.TOP_LEFT,
                ),
            ),
        )
        assertFalse(
            ConditionLogic.textExists(
                duplicatedTextNodes,
                AutomationCondition.TextExists(
                    query = "購入",
                    matchMode = TextMatchMode.EXACT,
                    region = ConditionRegion.BOTTOM_CENTER,
                ),
            ),
        )
    }
}
