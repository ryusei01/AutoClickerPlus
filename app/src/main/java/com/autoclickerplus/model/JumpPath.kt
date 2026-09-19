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

fun AutomationAction.JumpTo.resolvedTargetPath(): String =
    targetPath.trim().ifEmpty { targetNumber.toString() }

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
