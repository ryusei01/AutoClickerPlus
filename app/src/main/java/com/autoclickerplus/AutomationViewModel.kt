package com.autoclickerplus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autoclickerplus.data.AutomationRepository
import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.AutomationConfigEditor
import com.autoclickerplus.model.BranchSide
import com.autoclickerplus.model.RepeatMode
import com.autoclickerplus.model.ScriptLibrary
import com.autoclickerplus.model.ScriptLibraryEditor
import com.autoclickerplus.model.newBreak
import com.autoclickerplus.model.newIf
import com.autoclickerplus.model.newJumpTo
import com.autoclickerplus.model.newSwipe
import com.autoclickerplus.model.newTap
import com.autoclickerplus.model.newWait
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AutomationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AutomationRepository(application)

    val library: StateFlow<ScriptLibrary> = repository.library.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ScriptLibrary.default(),
    )

    val config: StateFlow<AutomationConfig> = library
        .map { it.activeScript.config }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = library.value.activeScript.config,
        )

    init {
        viewModelScope.launch { repository.migrateLegacyIfNeeded() }
    }

    fun addTap() = update { AutomationConfigEditor.add(it, it.newTap()) }

    fun addSwipe() = update { AutomationConfigEditor.add(it, it.newSwipe()) }

    fun addIf() = update { AutomationConfigEditor.add(it, it.newIf()) }

    fun addBreak() = update { AutomationConfigEditor.add(it, it.newBreak()) }

    fun addWait() = update { AutomationConfigEditor.add(it, it.newWait()) }

    fun addJumpTo() = update { AutomationConfigEditor.add(it, it.newJumpTo()) }

    fun addToBranch(ifBlockId: String, side: BranchSide, action: AutomationAction) =
        update { AutomationConfigEditor.addToBranch(it, ifBlockId, side, action) }

    fun addTapToBranch(ifBlockId: String, side: BranchSide) =
        update { AutomationConfigEditor.addToBranch(it, ifBlockId, side, it.newTap()) }

    fun addSwipeToBranch(ifBlockId: String, side: BranchSide) =
        update { AutomationConfigEditor.addToBranch(it, ifBlockId, side, it.newSwipe()) }

    fun addIfToBranch(ifBlockId: String, side: BranchSide) =
        update { AutomationConfigEditor.addToBranch(it, ifBlockId, side, it.newIf()) }

    fun addWaitToBranch(ifBlockId: String, side: BranchSide) =
        update { AutomationConfigEditor.addToBranch(it, ifBlockId, side, it.newWait()) }

    fun addJumpToBranch(ifBlockId: String, side: BranchSide) =
        update { AutomationConfigEditor.addToBranch(it, ifBlockId, side, it.newJumpTo()) }

    fun addBreakToBranch(ifBlockId: String, side: BranchSide) =
        update { AutomationConfigEditor.addToBranch(it, ifBlockId, side, it.newBreak()) }

    fun addCondition(ifBlockId: String, condition: AutomationCondition) =
        update { AutomationConfigEditor.addCondition(it, ifBlockId, condition) }

    fun replaceCondition(ifBlockId: String, condition: AutomationCondition) =
        update { AutomationConfigEditor.replaceCondition(it, ifBlockId, condition) }

    fun removeCondition(ifBlockId: String, conditionId: String) =
        update { AutomationConfigEditor.removeCondition(it, ifBlockId, conditionId) }

    fun replace(action: AutomationAction) =
        update { AutomationConfigEditor.replace(it, action) }

    fun remove(actionId: String) =
        update { AutomationConfigEditor.remove(it, actionId) }

    fun move(actionId: String, offset: Int) =
        update { AutomationConfigEditor.move(it, actionId, offset) }

    fun setRepeatMode(mode: RepeatMode) =
        update { AutomationConfigEditor.setRepeatMode(it, mode) }

    fun setRepeatCount(count: Int) =
        update { AutomationConfigEditor.setRepeatCount(it, count) }

    fun setDefaultTapWaitAfterMs(waitMs: Long) =
        update { AutomationConfigEditor.setDefaultTapWaitAfterMs(it, waitMs) }

    fun setDefaultSwipeWaitAfterMs(waitMs: Long) =
        update { AutomationConfigEditor.setDefaultSwipeWaitAfterMs(it, waitMs) }

    fun setDefaultIfWaitAfterMs(waitMs: Long) =
        update { AutomationConfigEditor.setDefaultIfWaitAfterMs(it, waitMs) }

    fun setDefaultWaitDurationMs(waitMs: Long) =
        update { AutomationConfigEditor.setDefaultWaitDurationMs(it, waitMs) }

    fun setDefaultWaitWaitAfterMs(waitMs: Long) =
        update { AutomationConfigEditor.setDefaultWaitWaitAfterMs(it, waitMs) }

    fun setDefaultJumpWaitAfterMs(waitMs: Long) =
        update { AutomationConfigEditor.setDefaultJumpWaitAfterMs(it, waitMs) }

    fun selectScript(scriptId: String) =
        updateLibrary { ScriptLibraryEditor.select(it, scriptId) }

    fun addScript(name: String) =
        updateLibrary { ScriptLibraryEditor.add(it, name) }

    fun renameActiveScript(name: String) =
        updateLibrary {
            ScriptLibraryEditor.rename(it, it.activeScriptId, name)
        }

    fun duplicateActiveScript() =
        updateLibrary {
            ScriptLibraryEditor.duplicate(it, it.activeScriptId)
        }

    fun deleteActiveScript() =
        updateLibrary {
            ScriptLibraryEditor.delete(it, it.activeScriptId)
        }

    fun replaceLibrary(library: ScriptLibrary) {
        viewModelScope.launch { repository.saveLibrary(library) }
    }

    private fun update(transform: (AutomationConfig) -> AutomationConfig) {
        updateLibrary { library ->
            ScriptLibraryEditor.updateActiveConfig(library, transform)
        }
    }

    private fun updateLibrary(transform: (ScriptLibrary) -> ScriptLibrary) {
        viewModelScope.launch { repository.updateLibrary(transform) }
    }
}
