package com.autoclickerplus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autoclickerplus.data.AutomationRepository
import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationConfig
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

    fun addTap() = update { it.copy(actions = it.actions + AutomationAction.Tap()) }

    fun addSwipe() = update { it.copy(actions = it.actions + AutomationAction.Swipe()) }

    fun replace(action: AutomationAction) = update { config ->
        config.copy(actions = config.actions.map { if (it.id == action.id) action else it })
    }

    fun remove(actionId: String) = update { config ->
        config.copy(actions = config.actions.filterNot { it.id == actionId })
    }

    fun move(actionId: String, offset: Int) = update { config ->
        val from = config.actions.indexOfFirst { it.id == actionId }
        if (from < 0) return@update config
        val to = (from + offset).coerceIn(0, config.actions.lastIndex)
        if (from == to) return@update config
        val actions = config.actions.toMutableList()
        val action = actions.removeAt(from)
        actions.add(to, action)
        config.copy(actions = actions)
    }

    fun setRepeatMode(mode: RepeatMode) = update { it.copy(repeatMode = mode) }

    fun setRepeatCount(count: Int) = update { it.copy(repeatCount = count.coerceIn(1, 100_000)) }

    private fun update(transform: (AutomationConfig) -> AutomationConfig) {
        val updated = transform(mutableConfig.value)
        mutableConfig.value = updated
        viewModelScope.launch { repository.save(updated) }
    }
}
