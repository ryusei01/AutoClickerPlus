package com.autoclickerplus.model

import org.junit.Assert.assertEquals
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
}
