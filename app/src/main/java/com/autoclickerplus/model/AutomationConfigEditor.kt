package com.autoclickerplus.model

object AutomationConfigEditor {
    fun add(config: AutomationConfig, action: AutomationAction): AutomationConfig =
        config.copy(actions = config.actions + action).normalized()

    fun replace(config: AutomationConfig, action: AutomationAction): AutomationConfig =
        config.copy(actions = replaceIn(config.actions, action)).normalized()

    fun remove(config: AutomationConfig, actionId: String): AutomationConfig =
        config.copy(actions = removeFrom(config.actions, actionId)).normalized()

    fun move(config: AutomationConfig, actionId: String, offset: Int): AutomationConfig {
        val (actions, changed) = moveIn(config.actions, actionId, offset)
        return if (changed) config.copy(actions = actions).normalized() else config
    }

    fun addToBranch(
        config: AutomationConfig,
        ifBlockId: String,
        side: BranchSide,
        action: AutomationAction,
    ): AutomationConfig = config.copy(
        actions = mapActions(config.actions) { current ->
            if (current is AutomationAction.IfBlock && current.id == ifBlockId) {
                when (side) {
                    BranchSide.THEN -> current.copy(thenActions = current.thenActions + action)
                    BranchSide.ELSE -> current.copy(elseActions = current.elseActions + action)
                }
            } else {
                current
            }
        },
    ).normalized()

    fun addCondition(
        config: AutomationConfig,
        ifBlockId: String,
        condition: AutomationCondition,
    ): AutomationConfig = updateIf(config, ifBlockId) {
        it.copy(conditions = it.conditions + condition)
    }

    fun replaceCondition(
        config: AutomationConfig,
        ifBlockId: String,
        condition: AutomationCondition,
    ): AutomationConfig = updateIf(config, ifBlockId) { block ->
        block.copy(
            conditions = block.conditions.map {
                if (it.id == condition.id) condition else it
            },
        )
    }

    fun removeCondition(
        config: AutomationConfig,
        ifBlockId: String,
        conditionId: String,
    ): AutomationConfig = updateIf(config, ifBlockId) { block ->
        val remaining = block.conditions.filterNot { it.id == conditionId }
        block.copy(
            conditions = remaining.ifEmpty { listOf(AutomationCondition.TextExists()) },
        )
    }

    fun findAction(config: AutomationConfig, actionId: String): AutomationAction? =
        findIn(config.actions, actionId)

    fun setRepeatMode(config: AutomationConfig, mode: RepeatMode): AutomationConfig =
        config.copy(repeatMode = mode).normalized()

    fun setRepeatCount(config: AutomationConfig, count: Int): AutomationConfig =
        config.copy(repeatCount = count).normalized()

    fun sequenceNumber(config: AutomationConfig, actionId: String): Int? =
        config.actions.indexOfFirst { it.id == actionId }
            .takeIf { it >= 0 }
            ?.plus(1)

    fun sequencePath(config: AutomationConfig, actionId: String): String? =
        findPath(config.actions, actionId, "")

    private fun updateIf(
        config: AutomationConfig,
        ifBlockId: String,
        transform: (AutomationAction.IfBlock) -> AutomationAction.IfBlock,
    ): AutomationConfig = config.copy(
        actions = mapActions(config.actions) {
            if (it is AutomationAction.IfBlock && it.id == ifBlockId) transform(it) else it
        },
    ).normalized()

    private fun replaceIn(
        actions: List<AutomationAction>,
        replacement: AutomationAction,
    ): List<AutomationAction> = actions.map { current ->
        when {
            current.id == replacement.id -> replacement
            current is AutomationAction.IfBlock -> current.copy(
                thenActions = replaceIn(current.thenActions, replacement),
                elseActions = replaceIn(current.elseActions, replacement),
            )
            else -> current
        }
    }

    private fun removeFrom(
        actions: List<AutomationAction>,
        actionId: String,
    ): List<AutomationAction> = actions
        .filterNot { it.id == actionId }
        .map { current ->
            if (current is AutomationAction.IfBlock) {
                current.copy(
                    thenActions = removeFrom(current.thenActions, actionId),
                    elseActions = removeFrom(current.elseActions, actionId),
                )
            } else {
                current
            }
        }

    private fun moveIn(
        actions: List<AutomationAction>,
        actionId: String,
        offset: Int,
    ): Pair<List<AutomationAction>, Boolean> {
        val index = actions.indexOfFirst { it.id == actionId }
        if (index >= 0) {
            val destination = (index + offset).coerceIn(0, actions.lastIndex)
            if (destination == index) return actions to false
            val changed = actions.toMutableList()
            changed.add(destination, changed.removeAt(index))
            return changed to true
        }
        actions.forEachIndexed { parentIndex, current ->
            if (current is AutomationAction.IfBlock) {
                val (thenActions, thenChanged) = moveIn(current.thenActions, actionId, offset)
                if (thenChanged) {
                    return actions.toMutableList().also {
                        it[parentIndex] = current.copy(thenActions = thenActions)
                    } to true
                }
                val (elseActions, elseChanged) = moveIn(current.elseActions, actionId, offset)
                if (elseChanged) {
                    return actions.toMutableList().also {
                        it[parentIndex] = current.copy(elseActions = elseActions)
                    } to true
                }
            }
        }
        return actions to false
    }

    private fun mapActions(
        actions: List<AutomationAction>,
        transform: (AutomationAction) -> AutomationAction,
    ): List<AutomationAction> = actions.map { original ->
        val nested = if (original is AutomationAction.IfBlock) {
            original.copy(
                thenActions = mapActions(original.thenActions, transform),
                elseActions = mapActions(original.elseActions, transform),
            )
        } else {
            original
        }
        transform(nested)
    }

    private fun findIn(
        actions: List<AutomationAction>,
        actionId: String,
    ): AutomationAction? {
        actions.forEach { action ->
            if (action.id == actionId) return action
            if (action is AutomationAction.IfBlock) {
                findIn(action.thenActions, actionId)?.let { return it }
                findIn(action.elseActions, actionId)?.let { return it }
            }
        }
        return null
    }

    private fun findPath(
        actions: List<AutomationAction>,
        actionId: String,
        prefix: String,
    ): String? {
        actions.forEachIndexed { index, action ->
            val path = if (prefix.isEmpty()) "${index + 1}" else "$prefix${index + 1}"
            if (action.id == actionId) return path
            if (action is AutomationAction.IfBlock) {
                findPath(action.thenActions, actionId, "$path-T")?.let { return it }
                findPath(action.elseActions, actionId, "$path-E")?.let { return it }
            }
        }
        return null
    }
}

object ScriptLibraryEditor {
    fun select(library: ScriptLibrary, scriptId: String): ScriptLibrary =
        if (library.scripts.any { it.id == scriptId }) {
            library.copy(activeScriptId = scriptId).normalized()
        } else {
            library
        }

    fun add(library: ScriptLibrary, name: String): ScriptLibrary {
        val script = AutomationScript(name = uniqueName(library, name))
        return library.copy(
            activeScriptId = script.id,
            scripts = library.scripts + script,
        ).normalized()
    }

    fun rename(library: ScriptLibrary, scriptId: String, name: String): ScriptLibrary =
        library.copy(
            scripts = library.scripts.map {
                if (it.id == scriptId) it.copy(name = name) else it
            },
        ).normalized()

    fun duplicate(library: ScriptLibrary, scriptId: String): ScriptLibrary {
        val source = library.scripts.firstOrNull { it.id == scriptId } ?: return library
        val copy = AutomationScript(
            name = uniqueName(library, "${source.name} のコピー"),
            config = source.config.withRegeneratedIds(),
        )
        return library.copy(
            activeScriptId = copy.id,
            scripts = library.scripts + copy,
        ).normalized()
    }

    fun delete(library: ScriptLibrary, scriptId: String): ScriptLibrary {
        if (library.scripts.size <= 1) return library
        val remaining = library.scripts.filterNot { it.id == scriptId }
        val activeId = if (library.activeScriptId == scriptId) {
            remaining.first().id
        } else {
            library.activeScriptId
        }
        return library.copy(activeScriptId = activeId, scripts = remaining).normalized()
    }

    fun updateActiveConfig(
        library: ScriptLibrary,
        transform: (AutomationConfig) -> AutomationConfig,
    ): ScriptLibrary = library.copy(
        scripts = library.scripts.map { script ->
            if (script.id == library.activeScriptId) {
                script.copy(config = transform(script.config))
            } else {
                script
            }
        },
    ).normalized()

    fun uniqueName(library: ScriptLibrary, requested: String): String {
        val base = requested.trim().ifEmpty { "新しいスクリプト" }.take(70)
        if (library.scripts.none { it.name == base }) return base
        var number = 2
        while (library.scripts.any { it.name == "$base ($number)" }) number++
        return "$base ($number)"
    }
}

fun AutomationConfig.withRegeneratedIds(): AutomationConfig = copy(
    actions = actions.map(AutomationAction::withRegeneratedIds),
).normalized()

fun AutomationAction.withRegeneratedIds(): AutomationAction = when (this) {
    is AutomationAction.Tap -> copy(id = java.util.UUID.randomUUID().toString())
    is AutomationAction.Swipe -> copy(id = java.util.UUID.randomUUID().toString())
    is AutomationAction.IfBlock -> copy(
        id = java.util.UUID.randomUUID().toString(),
        conditions = conditions.map(AutomationCondition::withRegeneratedId),
        thenActions = thenActions.map(AutomationAction::withRegeneratedIds),
        elseActions = elseActions.map(AutomationAction::withRegeneratedIds),
    )
    is AutomationAction.BreakLoop -> copy(id = java.util.UUID.randomUUID().toString())
}

fun AutomationCondition.withRegeneratedId(): AutomationCondition = when (this) {
    is AutomationCondition.TextExists -> copy(id = java.util.UUID.randomUUID().toString())
    is AutomationCondition.UiState -> copy(id = java.util.UUID.randomUUID().toString())
    is AutomationCondition.PixelColor -> copy(id = java.util.UUID.randomUUID().toString())
}
