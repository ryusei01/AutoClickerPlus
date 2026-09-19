package com.autoclickerplus.model

object AutomationConfigEditor {
    fun add(config: AutomationConfig, action: AutomationAction): AutomationConfig =
        config.copy(actions = config.actions + action).normalized()

    fun replace(config: AutomationConfig, action: AutomationAction): AutomationConfig =
        config.copy(
            actions = config.actions.map { current ->
                if (current.id == action.id) action else current
            },
        ).normalized()

    fun remove(config: AutomationConfig, actionId: String): AutomationConfig =
        config.copy(actions = config.actions.filterNot { it.id == actionId }).normalized()

    fun move(config: AutomationConfig, actionId: String, offset: Int): AutomationConfig {
        val from = config.actions.indexOfFirst { it.id == actionId }
        if (from < 0 || config.actions.isEmpty()) return config
        val to = (from + offset).coerceIn(0, config.actions.lastIndex)
        if (from == to) return config
        val actions = config.actions.toMutableList()
        actions.add(to, actions.removeAt(from))
        return config.copy(actions = actions).normalized()
    }

    fun setRepeatMode(config: AutomationConfig, mode: RepeatMode): AutomationConfig =
        config.copy(repeatMode = mode).normalized()

    fun setRepeatCount(config: AutomationConfig, count: Int): AutomationConfig =
        config.copy(repeatCount = count).normalized()

    fun sequenceNumber(config: AutomationConfig, actionId: String): Int? =
        config.actions.indexOfFirst { it.id == actionId }
            .takeIf { it >= 0 }
            ?.plus(1)
}
