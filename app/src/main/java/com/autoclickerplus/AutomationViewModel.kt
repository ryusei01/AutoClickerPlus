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

    fun addTap() = update { AutomationConfigEditor.add(it, AutomationAction.Tap()) }

    fun addSwipe() = update { AutomationConfigEditor.add(it, AutomationAction.Swipe()) }

    fun addIf() = update { AutomationConfigEditor.add(it, AutomationAction.IfBlock()) }

    fun addBreak() = update { AutomationConfigEditor.add(it, AutomationAction.BreakLoop()) }

    fun addWait() = update { AutomationConfigEditor.add(it, AutomationAction.Wait()) }

    fun addJumpTo() = update { AutomationConfigEditor.add(it, AutomationAction.JumpTo()) }

    fun addToBranch(ifBlockId: String, side: BranchSide, action: AutomationAction) =
        update { AutomationConfigEditor.addToBranch(it, ifBlockId, side, action) }

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
