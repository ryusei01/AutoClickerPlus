package com.autoclickerplus.engine

import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.ScreenRegion
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
        ),
        UiNodeSnapshot(
            values = listOf("準備中"),
            enabled = false,
            clickable = false,
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
    fun distinguishesIdenticalTextByScreenRegion() {
        val duplicateNodes = listOf(
            UiNodeSnapshot(
                values = listOf("発売中"),
                enabled = true,
                clickable = true,
                bounds = UiBounds(20, 100, 220, 180),
            ),
            UiNodeSnapshot(
                values = listOf("発売中"),
                enabled = false,
                clickable = false,
                bounds = UiBounds(20, 700, 220, 780),
            ),
        )

        assertTrue(
            ConditionLogic.uiStateMatches(
                duplicateNodes,
                AutomationCondition.UiState(
                    query = "発売中",
                    expectedEnabled = true,
                    expectedClickable = true,
                    region = ScreenRegion(0, 0, 300, 300),
                ),
            ),
        )
        assertFalse(
            ConditionLogic.uiStateMatches(
                duplicateNodes,
                AutomationCondition.UiState(
                    query = "発売中",
                    expectedEnabled = true,
                    expectedClickable = true,
                    region = ScreenRegion(0, 600, 300, 900),
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
