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
            screenLeft = 100,
            screenTop = 200,
            screenRight = 400,
            screenBottom = 300,
        ),
        UiNodeSnapshot(
            values = listOf("準備中"),
            enabled = false,
            clickable = false,
            screenLeft = 100,
            screenTop = 800,
            screenRight = 400,
            screenBottom = 900,
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
    fun textExistsFiltersNodesByRegion() {
        assertTrue(
            ConditionLogic.textExists(
                nodes,
                AutomationCondition.TextExists(
                    query = "購入する",
                    regionTop = 0,
                    regionBottom = 500,
                ),
            ),
        )
        assertFalse(
            ConditionLogic.textExists(
                nodes,
                AutomationCondition.TextExists(
                    query = "購入する",
                    regionTop = 500,
                    regionBottom = 1000,
                ),
            ),
        )
    }

    @Test
    fun regionDistinguishesSameTextAtDifferentPositions() {
        val sameTextNodes = listOf(
            UiNodeSnapshot(
                values = listOf("発売中"),
                enabled = true,
                clickable = true,
                screenLeft = 50,
                screenTop = 100,
                screenRight = 300,
                screenBottom = 180,
            ),
            UiNodeSnapshot(
                values = listOf("発売中"),
                enabled = true,
                clickable = true,
                screenLeft = 50,
                screenTop = 900,
                screenRight = 300,
                screenBottom = 980,
            ),
        )
        assertTrue(
            ConditionLogic.textExists(
                sameTextNodes,
                AutomationCondition.TextExists(
                    query = "発売中",
                    regionTop = 0,
                    regionBottom = 500,
                ),
            ),
        )
        assertTrue(
            ConditionLogic.textExists(
                sameTextNodes,
                AutomationCondition.TextExists(
                    query = "発売中",
                    regionTop = 500,
                ),
            ),
        )
        assertFalse(
            ConditionLogic.textExists(
                sameTextNodes,
                AutomationCondition.TextExists(
                    query = "発売中",
                    regionTop = 200,
                    regionBottom = 500,
                ),
            ),
        )
    }

    @Test
    fun noRegionMatchesAllNodes() {
        assertTrue(
            ConditionLogic.textExists(
                nodes,
                AutomationCondition.TextExists(query = "購入する"),
            ),
        )
        assertTrue(
            ConditionLogic.textExists(
                nodes,
                AutomationCondition.TextExists(query = "準備中"),
            ),
        )
    }

    @Test
    fun uiStateRespectsRegion() {
        assertTrue(
            ConditionLogic.uiStateMatches(
                nodes,
                AutomationCondition.UiState(
                    query = "購入する",
                    expectedEnabled = true,
                    regionTop = 0,
                    regionBottom = 500,
                ),
            ),
        )
        assertFalse(
            ConditionLogic.uiStateMatches(
                nodes,
                AutomationCondition.UiState(
                    query = "購入する",
                    expectedEnabled = true,
                    regionTop = 500,
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
