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
    fun comparesRgbChannelsUsingTolerance() {
        assertTrue(ConditionLogic.colorsMatch(0xFF102030.toInt(), 0xFF122331.toInt(), 3))
        assertFalse(ConditionLogic.colorsMatch(0xFF102030.toInt(), 0xFF202030.toInt(), 3))
    }

    @Test
    fun matchesTextOnlyInsideConfiguredRegion() {
        val nodes = listOf(
            UiNodeSnapshot(
                values = listOf("発売中"),
                enabled = true,
                clickable = true,
                left = 100,
                top = 500,
                right = 300,
                bottom = 600,
            ),
            UiNodeSnapshot(
                values = listOf("発売中"),
                enabled = true,
                clickable = true,
                left = 700,
                top = 1500,
                right = 900,
                bottom = 1600,
            ),
        )
        val condition = AutomationCondition.TextExists(
            query = "発売中",
            matchMode = TextMatchMode.EXACT,
            regionLeft = 650,
            regionTop = 1400,
            regionRight = 950,
            regionBottom = 1700,
        )

        assertTrue(ConditionLogic.textExists(nodes, condition))
        assertFalse(
            ConditionLogic.textExists(
                nodes,
                condition.copy(regionLeft = 0, regionTop = 0, regionRight = 400, regionBottom = 700),
            ),
        )
    }
}
