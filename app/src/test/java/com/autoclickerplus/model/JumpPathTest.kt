package com.autoclickerplus.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JumpPathTest {
    @Test
    fun collectActionPathsIncludesBranches() {
        val config = AutomationConfig(
            actions = listOf(
                AutomationAction.Tap(),
                AutomationAction.IfBlock(
                    thenActions = listOf(AutomationAction.Tap()),
                    elseActions = listOf(AutomationAction.Swipe()),
                ),
            ),
        )

        assertEquals(
            listOf("1", "2", "2-T1", "2-E1"),
            config.collectActionPaths(),
        )
    }

    @Test
    fun resolveJumpTargetSupportsBranchPaths() {
        val root = listOf(
            AutomationAction.Tap(),
            AutomationAction.IfBlock(
                thenActions = listOf(AutomationAction.Tap(), AutomationAction.Tap()),
                elseActions = listOf(AutomationAction.Swipe()),
            ),
            AutomationAction.Tap(),
        )

        val branchTarget = resolveJumpTarget(root, "2-T2")
        assertNotNull(branchTarget)
        assertEquals(1, branchTarget!!.index)
        assertEquals(2, branchTarget.rootIndexAfter)

        val rootTarget = resolveJumpTarget(root, "3")
        assertNotNull(rootTarget)
        assertEquals(2, rootTarget!!.index)
        assertEquals(2, rootTarget.rootIndexAfter)
    }

    @Test
    fun resolveJumpTargetRejectsInvalidPath() {
        val root = listOf(
            AutomationAction.Tap(),
            AutomationAction.Tap(),
        )
        assertNull(resolveJumpTarget(root, "2-T1"))
        assertNull(resolveJumpTarget(root, "9"))
    }
}
