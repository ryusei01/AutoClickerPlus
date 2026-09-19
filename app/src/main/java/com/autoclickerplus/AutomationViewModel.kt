package com.autoclickerplus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autoclickerplus.data.AutomationRepository
import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.AutomationConfigEditor
import com.autoclickerplus.model.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AutomationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AutomationRepository(application)
    private val mutableConfig = MutableStateFlow(AutomationConfig())
    val config: StateFlow<AutomationConfig> = mutableConfig.asStateFlow()

    init {
        viewModelScope.launch {
            repository.config.collect { mutableConfig.value = it }
        }
    }

    fun addTap() = update { AutomationConfigEditor.add(it, AutomationAction.Tap()) }

    fun addSwipe() = update { AutomationConfigEditor.add(it, AutomationAction.Swipe()) }

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

    private fun update(transform: (AutomationConfig) -> AutomationConfig) {
        val updated = transform(mutableConfig.value)
        mutableConfig.value = updated
        viewModelScope.launch { repository.save(updated) }
    }
}
