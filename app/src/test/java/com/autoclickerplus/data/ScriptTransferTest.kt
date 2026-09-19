package com.autoclickerplus.data

import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.AutomationScript
import com.autoclickerplus.model.ScriptLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTransferTest {
    private val transfer = ScriptTransfer()

    @Test
    fun exportsAllScriptsAndImportsThemAsNew() {
        val first = AutomationScript(
            id = "script-one",
            name = "テスト",
            config = AutomationConfig(actions = listOf(AutomationAction.Tap(id = "tap-one"))),
        )
        val second = AutomationScript(id = "script-two", name = "テスト2")
        val source = ScriptLibrary(
            activeScriptId = first.id,
            scripts = listOf(first, second),
        )

        val exported = transfer.exportAll(source)
        val currentScript = AutomationScript(name = "テスト")
        val current = ScriptLibrary(
            activeScriptId = currentScript.id,
            scripts = listOf(currentScript),
        )
        val imported = transfer.importAsNew(current, exported).getOrThrow()

        assertEquals(3, imported.scripts.size)
        assertEquals(imported.scripts[1].id, imported.activeScriptId)
        assertEquals("テスト (2)", imported.scripts[1].name)
        assertNotEquals("script-one", imported.scripts[1].id)
        assertNotEquals(
            "tap-one",
            imported.scripts[1].config.actions.single().id,
        )
    }

    @Test
    fun rejectsEmptyScriptBundle() {
        val empty = """{"formatVersion":1,"exportedAtEpochMs":1,"scripts":[]}"""
        assertTrue(transfer.importAsNew(ScriptLibrary.default(), empty).isFailure)
    }
}
