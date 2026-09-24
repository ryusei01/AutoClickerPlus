package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationCondition
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
            bounds = AutomationCondition.ScreenRegion(0, 0, 200, 100),
        ),
        UiNodeSnapshot(
            values = listOf("準備中"),
            enabled = false,
            clickable = false,
            bounds = AutomationCondition.ScreenRegion(300, 0, 500, 100),
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
    fun matchesTextOnlyInsideSpecifiedRegion() {
        assertTrue(
            ConditionLogic.textExists(
                nodes,
                AutomationCondition.TextExists(
                    query = "購入する",
                    matchMode = TextMatchMode.EXACT,
                    region = AutomationCondition.ScreenRegion(0, 0, 240, 120),
                ),
            ),
        )
        assertFalse(
            ConditionLogic.textExists(
                nodes,
                AutomationCondition.TextExists(
                    query = "購入する",
                    matchMode = TextMatchMode.EXACT,
                    region = AutomationCondition.ScreenRegion(260, 0, 520, 120),
                ),
            ),
        )
    }

    @Test
    fun comparesRgbChannelsUsingTolerance() {
        assertTrue(ConditionLogic.colorsMatch(0xFF102030.toInt(), 0xFF122331.toInt(), 3))
        assertFalse(ConditionLogic.colorsMatch(0xFF102030.toInt(), 0xFF202030.toInt(), 3))
    }
}
