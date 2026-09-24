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
    fun sameTextIsDistinguishedByRegion() {
        val nodes = listOf(
            UiNodeSnapshot(
                values = listOf("発売中"),
                enabled = true,
                clickable = true,
                left = 10,
                top = 10,
                right = 80,
                bottom = 40,
            ),
            UiNodeSnapshot(
                values = listOf("発売中"),
                enabled = true,
                clickable = true,
                left = 400,
                top = 800,
                right = 520,
                bottom = 860,
            ),
        )
        val upper = AutomationCondition.TextExists(
            query = "発売中",
            matchMode = TextMatchMode.EXACT,
            region = ScreenRegion(left = 0, top = 0, right = 200, bottom = 200),
        )
        val lower = upper.copy(
            region = ScreenRegion(left = 300, top = 700, right = 600, bottom = 900),
        )

        assertTrue(ConditionLogic.textExists(nodes, upper))
        assertTrue(ConditionLogic.textExists(nodes, lower))
        assertFalse(
            ConditionLogic.textExists(
                nodes,
                upper.copy(region = ScreenRegion(left = 200, top = 200, right = 300, bottom = 300)),
            ),
        )
        assertTrue(ConditionLogic.textExists(nodes, upper.copy(region = null)))
        assertFalse(
            ConditionLogic.uiStateMatches(
                nodes,
                AutomationCondition.UiState(
                    query = "発売中",
                    matchMode = TextMatchMode.EXACT,
                    expectedEnabled = true,
                    region = ScreenRegion(left = 200, top = 200, right = 300, bottom = 300),
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
