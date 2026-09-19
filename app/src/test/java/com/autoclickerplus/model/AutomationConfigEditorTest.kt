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
    fun replacementAndGlobalSettingsAreNormalized() {
        val config = AutomationConfigEditor.add(AutomationConfig(), first)
        val replaced = AutomationConfigEditor.replace(
            config,
            first.copy(waitAfterMs = -10, jitterPx = 99),
        )
        val action = replaced.actions.single() as AutomationAction.Tap

        assertEquals(0L, action.waitAfterMs)
        assertEquals(10, action.jitterPx)
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
    fun ifBranchJumpTargetsAreNormalized() {
        val block = AutomationAction.IfBlock(
            id = "if",
            thenJumpTo = 0,
            elseJumpTo = -3,
        )
        val config = AutomationConfigEditor.replace(
            AutomationConfig(actions = listOf(block)),
            block.copy(thenJumpTo = 2, elseJumpTo = 0),
        )
        val saved = config.actions.single() as AutomationAction.IfBlock
        assertEquals(2, saved.thenJumpTo)
        assertNull(saved.elseJumpTo)
        val waits = AutomationConfigEditor.replace(
            config,
            saved.copy(thenWaitMs = -8, elseWaitMs = 250),
        ).actions.single() as AutomationAction.IfBlock
        assertEquals(0L, waits.thenWaitMs)
        assertEquals(250L, waits.elseWaitMs)
        assertEquals("2番へ進む", jumpTargetLabel(2, 1))
        assertEquals("1.5", formatWaitSeconds(1_500L))
        assertEquals(1_500L, parseWaitSeconds("1.5"))
        val wait = AutomationConfigEditor.add(
            AutomationConfig(),
            AutomationAction.Wait(durationMs = 9_999_999L),
        ).actions.single() as AutomationAction.Wait
        assertEquals(MAX_WAIT_MS, wait.durationMs)
        assertEquals("1番へ戻る", jumpTargetLabel(1, 3))
        assertEquals("次へ", jumpTargetLabel(null, 2))
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
