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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AutomationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AutomationRepository(application)
    private val mutableLibrary = MutableStateFlow(ScriptLibrary.default())
    val library: StateFlow<ScriptLibrary> = mutableLibrary.asStateFlow()
    private val mutableConfig = MutableStateFlow(mutableLibrary.value.activeScript.config)
    val config: StateFlow<AutomationConfig> = mutableConfig.asStateFlow()

    init {
        viewModelScope.launch {
            repository.migrateLegacyIfNeeded()
            repository.library.collect {
                mutableLibrary.value = it
                mutableConfig.value = it.activeScript.config
            }
        }
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
        mutableLibrary.value = library
        mutableConfig.value = library.activeScript.config
        viewModelScope.launch { repository.saveLibrary(library) }
    }

    private fun update(transform: (AutomationConfig) -> AutomationConfig) {
        updateLibrary { library ->
            ScriptLibraryEditor.updateActiveConfig(library, transform)
        }
    }

    private fun updateLibrary(transform: (ScriptLibrary) -> ScriptLibrary) {
        val updated = transform(mutableLibrary.value)
        mutableLibrary.value = updated
        mutableConfig.value = updated.activeScript.config
        viewModelScope.launch { repository.saveLibrary(updated) }
    }
}
