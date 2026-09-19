package com.autoclickerplus.data

import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationScript
import com.autoclickerplus.model.ScriptLibrary
import com.autoclickerplus.model.ScriptLibraryEditor
import com.autoclickerplus.model.normalized
import com.autoclickerplus.model.withRegeneratedIds
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ScriptExportBundle(
    val formatVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val scripts: List<AutomationScript>,
)

class ScriptTransfer {
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun exportAll(library: ScriptLibrary): String = json.encodeToString(
        ScriptExportBundle(
            exportedAtEpochMs = System.currentTimeMillis(),
            scripts = library.normalized().scripts,
        ),
    )

    fun importAsNew(current: ScriptLibrary, source: String): Result<ScriptLibrary> = runCatching {
        require(source.length <= MAX_IMPORT_CHARS) { "ファイルが大きすぎます" }
        val bundle = json.decodeFromString<ScriptExportBundle>(source)
        require(bundle.formatVersion == 1) { "未対応のExport形式です" }
        require(bundle.scripts.isNotEmpty()) { "スクリプトが含まれていません" }
        require(bundle.scripts.size <= MAX_SCRIPTS) { "スクリプト数が多すぎます" }
        bundle.scripts.forEach { imported ->
            imported.config.normalized()
        }

        var result = current.normalized()
        var firstImportedId: String? = null
        bundle.scripts.forEach { imported ->
            require(countActions(imported.config.actions) <= MAX_ACTIONS_PER_SCRIPT) {
                "アクション数が多すぎます"
            }
            require(maxDepth(imported.config.actions) <= MAX_NESTING_DEPTH) {
                "IFの入れ子が深すぎます"
            }
            val script = AutomationScript(
                id = UUID.randomUUID().toString(),
                name = ScriptLibraryEditor.uniqueName(result, imported.name),
                config = imported.config.withRegeneratedIds(),
            )
            if (firstImportedId == null) firstImportedId = script.id
            result = result.copy(scripts = result.scripts + script).normalized()
        }
        result.copy(activeScriptId = requireNotNull(firstImportedId)).normalized()
    }

    private fun countActions(actions: List<AutomationAction>): Int =
        actions.sumOf { action ->
            1 + if (action is AutomationAction.IfBlock) {
                countActions(action.thenActions) + countActions(action.elseActions)
            } else {
                0
            }
        }

    private fun maxDepth(actions: List<AutomationAction>, depth: Int = 0): Int =
        actions.maxOfOrNull { action ->
            if (action is AutomationAction.IfBlock) {
                maxOf(
                    depth + 1,
                    maxDepth(action.thenActions, depth + 1),
                    maxDepth(action.elseActions, depth + 1),
                )
            } else {
                depth
            }
        } ?: depth

    private companion object {
        const val MAX_IMPORT_CHARS = 5_000_000
        const val MAX_SCRIPTS = 200
        const val MAX_ACTIONS_PER_SCRIPT = 10_000
        const val MAX_NESTING_DEPTH = 10
    }
}
