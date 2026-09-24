package com.autoclickerplus.model

data class JumpTarget(
    val actions: List<AutomationAction>,
    val index: Int,
    /** 分岐内ジャンプ完了後にルートで進める 0-based index */
    val rootIndexAfter: Int,
)

fun AutomationConfig.collectActionPaths(): List<String> =
    collectActionPaths(actions)

fun collectActionPaths(
    actions: List<AutomationAction>,
    prefix: String = "",
): List<String> {
    val result = mutableListOf<String>()
    actions.forEachIndexed { index, action ->
        val path = if (prefix.isEmpty()) "${index + 1}" else "$prefix${index + 1}"
        result += path
        if (action is AutomationAction.IfBlock) {
            result += collectActionPaths(action.thenActions, "$path-T")
            result += collectActionPaths(action.elseActions, "$path-E")
        }
    }
    return result
}

/** actionId → フロー番号パス（例: 2-E1） */
fun collectActionIdPaths(
    actions: List<AutomationAction>,
    prefix: String = "",
): Map<String, String> {
    val result = linkedMapOf<String, String>()
    actions.forEachIndexed { index, action ->
        val path = if (prefix.isEmpty()) "${index + 1}" else "$prefix${index + 1}"
        result[action.id] = path
        if (action is AutomationAction.IfBlock) {
            result += collectActionIdPaths(action.thenActions, "$path-T")
            result += collectActionIdPaths(action.elseActions, "$path-E")
        }
    }
    return result
}

/**
 * 削除・並べ替えの前後で、番号へ の移動先を「同じアクション」に向け直す。
 * 移動先アクション自体が消えていれば未設定にする。
 */
fun remapJumpTargets(
    actions: List<AutomationAction>,
    pathByIdBefore: Map<String, String>,
): List<AutomationAction> {
    val idByPathBefore = pathByIdBefore.entries.associate { (id, path) -> path to id }
    val pathByIdAfter = collectActionIdPaths(actions)

    fun remap(action: AutomationAction): AutomationAction = when (action) {
        is AutomationAction.JumpTo -> {
            val oldPath = action.resolvedTargetPath()
            if (oldPath.isEmpty()) {
                action
            } else {
                val targetId = idByPathBefore[oldPath]
                val newPath = targetId?.let { pathByIdAfter[it] }
                when {
                    newPath == null && targetId != null -> action.copy(
                        targetPath = "",
                        targetNumber = 0,
                    )
                    newPath != null && newPath != oldPath -> action.copy(
                        targetPath = newPath,
                        targetNumber = newPath.substringBefore('-')
                            .toIntOrNull()
                            ?.coerceAtLeast(1)
                            ?: action.targetNumber,
                    )
                    else -> action
                }
            }
        }
        is AutomationAction.IfBlock -> action.copy(
            thenActions = action.thenActions.map(::remap),
            elseActions = action.elseActions.map(::remap),
        )
        else -> action
    }

    return actions.map(::remap)
}

fun resolveJumpTarget(
    rootActions: List<AutomationAction>,
    targetPath: String,
): JumpTarget? {
    var remaining = targetPath.trim()
    if (remaining.isEmpty()) return null

    var current = rootActions
    var ifRootIndex: Int? = null

    while (remaining.isNotEmpty()) {
        val numMatch = Regex("^(\\d+)").find(remaining) ?: return null
        val number = numMatch.groupValues[1].toInt()
        remaining = remaining.removePrefix(numMatch.value)
        if (number !in 1..current.size) return null
        val index = number - 1

        if (remaining.isEmpty()) {
            return JumpTarget(
                actions = current,
                index = index,
                rootIndexAfter = if (current === rootActions) index else ifRootIndex!! + 1,
            )
        }

        if (!remaining.startsWith("-")) return null
        remaining = remaining.removePrefix("-")
        val branchChar = remaining.firstOrNull() ?: return null
        if (branchChar != 'T' && branchChar != 'E') return null
        remaining = remaining.drop(1)

        val block = current[index] as? AutomationAction.IfBlock ?: return null
        if (current === rootActions) {
            ifRootIndex = index
        }
        current = if (branchChar == 'T') block.thenActions else block.elseActions
    }
    return null
}

fun AutomationAction.JumpTo.resolvedTargetPath(): String {
    val trimmed = targetPath.trim()
    if (trimmed.isNotEmpty()) return trimmed
    return if (targetNumber > 0) targetNumber.toString() else ""
}

fun jumpTargetLabel(targetPath: String, fromPath: String = ""): String {
    val target = targetPath.trim()
    if (target.isEmpty()) return "番号未設定"
    if (fromPath.isEmpty()) return "${target}へ"
    return when (compareActionPaths(fromPath, target)) {
        PathRelation.BEFORE -> "${target}へ戻る"
        PathRelation.AFTER -> "${target}へ進む"
        PathRelation.SAME -> "${target}へ"
    }
}

private enum class PathRelation {
    BEFORE,
    AFTER,
    SAME,
}

private fun compareActionPaths(fromPath: String, toPath: String): PathRelation {
    if (fromPath == toPath) return PathRelation.SAME
    val fromParts = splitActionPath(fromPath)
    val toParts = splitActionPath(toPath)
    val shared = fromParts.zip(toParts).takeWhile { (left, right) -> left == right }.size
    if (shared == fromParts.size && shared < toParts.size) return PathRelation.AFTER
    if (shared == toParts.size && shared < fromParts.size) return PathRelation.BEFORE
    return if (toPath > fromPath) PathRelation.AFTER else PathRelation.BEFORE
}

private fun splitActionPath(path: String): List<String> {
    val parts = mutableListOf<String>()
    var remaining = path.trim()
    while (remaining.isNotEmpty()) {
        val numMatch = Regex("^(\\d+)").find(remaining) ?: break
        parts += numMatch.groupValues[1]
        remaining = remaining.removePrefix(numMatch.value)
        if (remaining.startsWith("-")) {
            remaining = remaining.removePrefix("-")
            if (remaining.isNotEmpty() && (remaining.first() == 'T' || remaining.first() == 'E')) {
                parts += remaining.first().toString()
                remaining = remaining.drop(1)
            }
        }
    }
    return parts
}
