package com.autoclickerplus.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationConfigEditorTest {
    private val first = AutomationAction.Tap(id = "tap")
    private val second = AutomationAction.Swipe(id = "swipe")

    @Test
    fun addRemoveAndMovePreserveSequenceNumbers() {
        var config = AutomationConfig()
        config = AutomationConfigEditor.add(config, first)
        config = AutomationConfigEditor.add(config, second)

        assertEquals(1, AutomationConfigEditor.sequenceNumber(config, "tap"))
        assertEquals(2, AutomationConfigEditor.sequenceNumber(config, "swipe"))

        config = AutomationConfigEditor.move(config, "swipe", -1)
        assertEquals(listOf("swipe", "tap"), config.actions.map { it.id })
        assertEquals(1, AutomationConfigEditor.sequenceNumber(config, "swipe"))
        assertEquals(2, AutomationConfigEditor.sequenceNumber(config, "tap"))

        config = AutomationConfigEditor.remove(config, "swipe")
        assertEquals(1, AutomationConfigEditor.sequenceNumber(config, "tap"))
        assertNull(AutomationConfigEditor.sequenceNumber(config, "swipe"))
    }

    @Test
    fun collectPickableActionsIncludesNestedTapAndSwipe() {
        val nestedTap = AutomationAction.Tap(id = "nested-tap")
        val nestedSwipe = AutomationAction.Swipe(id = "nested-swipe")
        val block = AutomationAction.IfBlock(
            id = "if",
            thenActions = listOf(nestedTap),
            elseActions = listOf(nestedSwipe),
        )
        var config = AutomationConfig(actions = listOf(first, block, second))

        val pickables = AutomationConfigEditor.collectPickableActions(config)

        assertEquals(
            listOf("1", "2-T1", "2-E1", "3"),
            pickables.map { it.path },
        )
        assertEquals(
            listOf("tap", "nested-tap", "nested-swipe", "swipe"),
            pickables.map { it.action.id },
        )
    }

    @Test
    fun replacementAndGlobalSettingsAreNormalized() {
        val config = AutomationConfigEditor.add(AutomationConfig(), first)
        val replaced = AutomationConfigEditor.replace(
            config,
            first.copy(waitAfterMs = -10, jitterPx = 99),
        )
        val action = replaced.actions.single() as AutomationAction.Tap

        assertEquals(0L, action.waitAfterMs)
        assertEquals(50, action.jitterPx)
        assertEquals(
            1,
            AutomationConfigEditor.setRepeatCount(replaced, -5).repeatCount,
        )
    }

    @Test
    fun editsNestedBranchesByStableId() {
        val block = AutomationAction.IfBlock(id = "if")
        var config = AutomationConfig(actions = listOf(block))
        config = AutomationConfigEditor.addToBranch(
            config,
            "if",
            BranchSide.THEN,
            AutomationAction.Tap(id = "nested"),
        )

        assertEquals("1-T1", AutomationConfigEditor.sequencePath(config, "nested"))
        config = AutomationConfigEditor.replace(
            config,
            (AutomationConfigEditor.findAction(config, "nested") as AutomationAction.Tap)
                .copy(waitAfterMs = 900),
        )
        assertEquals(
            900L,
            (AutomationConfigEditor.findAction(config, "nested") as AutomationAction.Tap)
                .waitAfterMs,
        )
        config = AutomationConfigEditor.remove(config, "nested")
        assertNull(AutomationConfigEditor.findAction(config, "nested"))
    }

    @Test
    fun scriptLibraryAlwaysKeepsOneActiveScript() {
        var library = ScriptLibrary.default()
        val originalId = library.activeScriptId
        library = ScriptLibraryEditor.add(library, "別設定")
        assertEquals(2, library.scripts.size)
        assertNotEquals(originalId, library.activeScriptId)

        library = ScriptLibraryEditor.delete(library, library.activeScriptId)
        assertEquals(1, library.scripts.size)
        assertEquals(originalId, library.activeScriptId)
        assertEquals(library, ScriptLibraryEditor.delete(library, originalId))
    }

    @Test
    fun selectingAnotherScriptChangesActiveWithoutDroppingOthers() {
        var library = ScriptLibrary.default()
        val firstId = library.activeScriptId
        library = ScriptLibraryEditor.updateActiveConfig(library) {
            it.copy(actions = listOf(AutomationAction.Tap(id = "tap-a")))
        }
        library = ScriptLibraryEditor.add(library, "別設定")
        val secondId = library.activeScriptId
        library = ScriptLibraryEditor.updateActiveConfig(library) {
            it.copy(actions = listOf(AutomationAction.Swipe(id = "swipe-b")))
        }

        library = ScriptLibraryEditor.select(library, firstId)
        assertEquals(firstId, library.activeScriptId)
        assertEquals(2, library.scripts.size)
        assertEquals("tap-a", library.activeScript.config.actions.single().id)

        library = ScriptLibraryEditor.select(library, secondId)
        assertEquals("swipe-b", library.activeScript.config.actions.single().id)
    }

    @Test
    fun legacyConfigBecomesDefaultScriptWithoutLosingActions() {
        val legacy = AutomationConfig(
            actions = listOf(AutomationAction.Tap(id = "legacy-tap")),
            repeatMode = RepeatMode.COUNT,
            repeatCount = 3,
        )

        val migrated = ScriptLibrary.fromLegacy(legacy)

        assertEquals("デフォルト", migrated.activeScript.name)
        assertEquals("legacy-tap", migrated.activeScript.config.actions.single().id)
        assertEquals(3, migrated.activeScript.config.repeatCount)
    }

    @Test
    fun jumpToAndWaitActionsAreNormalized() {
        val unset = AutomationConfigEditor.add(
            AutomationConfig(),
            AutomationAction.JumpTo(targetPath = "", targetNumber = 0),
        ).actions.single() as AutomationAction.JumpTo
        assertEquals("", unset.targetPath)
        assertEquals(0, unset.targetNumber)
        assertEquals("番号未設定", jumpTargetLabel(unset.resolvedTargetPath(), "1"))

        val jump = AutomationConfigEditor.add(
            AutomationConfig(),
            AutomationAction.JumpTo(targetNumber = 2),
        ).actions.single() as AutomationAction.JumpTo
        assertEquals(2, jump.targetNumber)
        assertEquals("2", jump.targetPath)
        assertEquals("2へ進む", jumpTargetLabel("2", "1"))
        assertEquals("1へ戻る", jumpTargetLabel("1", "3"))
        val wait = AutomationConfigEditor.add(
            AutomationConfig(),
            AutomationAction.Wait(durationMs = 9_999_999L),
        ).actions.single() as AutomationAction.Wait
        assertEquals(MAX_WAIT_MS, wait.durationMs)
    }

    @Test
    fun removingActionRemapsJumpTargetsToSameAction() {
        val tapA = AutomationAction.Tap(id = "a")
        val tapB = AutomationAction.Tap(id = "b")
        val tapC = AutomationAction.Tap(id = "c")
        val jumpToC = AutomationAction.JumpTo(id = "jump", targetPath = "3")
        var config = AutomationConfig(actions = listOf(tapA, tapB, tapC, jumpToC))

        config = AutomationConfigEditor.remove(config, "b")

        assertEquals(listOf("a", "c", "jump"), config.actions.map { it.id })
        val jump = config.actions[2] as AutomationAction.JumpTo
        assertEquals("2", jump.targetPath)
        assertEquals("c", AutomationConfigEditor.findAction(config, "c")?.id)
    }

    @Test
    fun removingJumpTargetClearsJumpTo() {
        val tapA = AutomationAction.Tap(id = "a")
        val tapB = AutomationAction.Tap(id = "b")
        val jumpToB = AutomationAction.JumpTo(id = "jump", targetPath = "2")
        var config = AutomationConfig(actions = listOf(tapA, tapB, jumpToB))

        config = AutomationConfigEditor.remove(config, "b")

        val jump = config.actions[1] as AutomationAction.JumpTo
        assertEquals("", jump.targetPath)
        assertEquals(0, jump.targetNumber)
        assertEquals("番号未設定", jumpTargetLabel(jump.resolvedTargetPath()))
    }

    @Test
    fun movingActionRemapsNestedJumpTargets() {
        val tap = AutomationAction.Tap(id = "tap")
        val nestedJump = AutomationAction.JumpTo(id = "jump", targetPath = "1")
        val block = AutomationAction.IfBlock(
            id = "if",
            elseActions = listOf(nestedJump),
        )
        val swipe = AutomationAction.Swipe(id = "swipe")
        var config = AutomationConfig(actions = listOf(tap, block, swipe))

        // swipe を先頭へ → tap は 2 番に
        config = AutomationConfigEditor.move(config, "swipe", -2)

        assertEquals(listOf("swipe", "tap", "if"), config.actions.map { it.id })
        val jump = (
            AutomationConfigEditor.findAction(config, "jump") as AutomationAction.JumpTo
        )
        assertEquals("2", jump.targetPath)
    }

    @Test
    fun removingNestedActionRemapsSiblingJumpInBranch() {
        val first = AutomationAction.Tap(id = "t1")
        val second = AutomationAction.Tap(id = "t2")
        val jump = AutomationAction.JumpTo(id = "jump", targetPath = "1-T2")
        val block = AutomationAction.IfBlock(
            id = "if",
            thenActions = listOf(first, second, jump),
        )
        var config = AutomationConfig(actions = listOf(block))

        config = AutomationConfigEditor.remove(config, "t1")

        val remapped = AutomationConfigEditor.findAction(config, "jump") as AutomationAction.JumpTo
        assertEquals("1-T1", remapped.targetPath)
        assertEquals("t2", AutomationConfigEditor.findAction(config, "t2")?.id)
    }

    @Test
    fun defaultTapWaitAfterMsAppliesToNewTap() {
        val config = AutomationConfigEditor.setDefaultTapWaitAfterMs(
            AutomationConfig(),
            750L,
        )
        assertEquals(750L, config.defaultTapWaitAfterMs)
        val tap = AutomationConfigEditor.add(config, config.newTap())
            .actions.single() as AutomationAction.Tap
        assertEquals(750L, tap.waitAfterMs)
    }

    @Test
    fun actionDefaultsApplyToNewActions() {
        var config = AutomationConfig()
        config = AutomationConfigEditor.setDefaultSwipeWaitAfterMs(config, 111L)
        config = AutomationConfigEditor.setDefaultIfWaitAfterMs(config, 222L)
        config = AutomationConfigEditor.setDefaultWaitDurationMs(config, 333L)
        config = AutomationConfigEditor.setDefaultWaitWaitAfterMs(config, 444L)
        config = AutomationConfigEditor.setDefaultJumpWaitAfterMs(config, 555L)

        val swipe = config.newSwipe()
        val ifBlock = config.newIf()
        val wait = config.newWait()
        val jump = config.newJumpTo()

        assertEquals(111L, swipe.waitAfterMs)
        assertEquals(222L, ifBlock.waitAfterMs)
        assertEquals(333L, wait.durationMs)
        assertEquals(444L, wait.waitAfterMs)
        assertEquals(555L, jump.waitAfterMs)
    }

    @Test
    fun uniqueNameAddsNumberWhenDuplicated() {
        val library = ScriptLibrary.default().let {
            it.copy(scripts = listOf(it.activeScript.copy(name = "テスト")))
        }
        assertEquals("テスト (2)", ScriptLibraryEditor.uniqueName(library, "テスト"))
        assertEquals("別設定", ScriptLibraryEditor.uniqueName(library, "別設定"))
    }
}
