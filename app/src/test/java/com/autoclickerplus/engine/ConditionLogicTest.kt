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
            bounds = NodeBounds(0, 0, 540, 200),
        ),
        UiNodeSnapshot(
            values = listOf("準備中"),
            enabled = false,
            clickable = false,
            bounds = NodeBounds(0, 800, 540, 1000),
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
    fun regionFilterMatchesNodeInsideArea() {
        assertTrue(
            ConditionLogic.textExists(
                nodes,
                AutomationCondition.TextExists(
                    query = "購入",
                    matchMode = TextMatchMode.CONTAINS,
                    region = ScreenRegion(left = 0, top = 0, right = 1080, bottom = 400),
                ),
            ),
        )
    }

    @Test
    fun regionFilterExcludesNodeOutsideArea() {
        assertFalse(
            ConditionLogic.textExists(
                nodes,
                AutomationCondition.TextExists(
                    query = "購入",
                    matchMode = TextMatchMode.CONTAINS,
                    region = ScreenRegion(left = 0, top = 500, right = 1080, bottom = 1200),
                ),
            ),
        )
    }

    @Test
    fun regionFilterDistinguishesSameTextInDifferentAreas() {
        val nodesWithDups = listOf(
            UiNodeSnapshot(
                values = listOf("OK"),
                enabled = true,
                clickable = true,
                bounds = NodeBounds(0, 100, 540, 200),
            ),
            UiNodeSnapshot(
                values = listOf("OK"),
                enabled = true,
                clickable = true,
                bounds = NodeBounds(0, 700, 540, 800),
            ),
        )
        assertTrue(
            ConditionLogic.textExists(
                nodesWithDups,
                AutomationCondition.TextExists(
                    query = "OK",
                    region = ScreenRegion(left = 0, top = 0, right = 1080, bottom = 400),
                ),
            ),
        )
        assertFalse(
            ConditionLogic.textExists(
                nodesWithDups,
                AutomationCondition.TextExists(
                    query = "OK",
                    region = ScreenRegion(left = 0, top = 0, right = 1080, bottom = 400),
                ),
            ).not(),
        )
        assertFalse(
            ConditionLogic.textExists(
                nodesWithDups,
                AutomationCondition.TextExists(
                    query = "OK",
                    region = ScreenRegion(left = 0, top = 900, right = 1080, bottom = 1200),
                ),
            ),
        )
    }

    @Test
    fun regionFilterNullMeansNoRestriction() {
        assertTrue(
            ConditionLogic.textExists(
                nodes,
                AutomationCondition.TextExists(
                    query = "準備中",
                    region = null,
                ),
            ),
        )
    }

    @Test
    fun regionFilterWithNoBoundsExcludesNode() {
        val noBoundsNodes = listOf(
            UiNodeSnapshot(values = listOf("購入する"), enabled = true, clickable = true),
        )
        assertFalse(
            ConditionLogic.textExists(
                noBoundsNodes,
                AutomationCondition.TextExists(
                    query = "購入する",
                    region = ScreenRegion(0, 0, 1080, 400),
                ),
            ),
        )
    }

    @Test
    fun regionFilterUiStateMatchesNodeInsideArea() {
        assertTrue(
            ConditionLogic.uiStateMatches(
                nodes,
                AutomationCondition.UiState(
                    query = "購入する",
                    expectedEnabled = true,
                    region = ScreenRegion(left = 0, top = 0, right = 1080, bottom = 400),
                ),
            ),
        )
        assertFalse(
            ConditionLogic.uiStateMatches(
                nodes,
                AutomationCondition.UiState(
                    query = "購入する",
                    expectedEnabled = true,
                    region = ScreenRegion(left = 0, top = 500, right = 1080, bottom = 1200),
                ),
            ),
        )
    }
}
