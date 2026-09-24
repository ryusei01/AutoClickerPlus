package com.autoclickerplus.model

import kotlin.math.roundToInt

fun AutomationAction.flowTitle(): String = when (this) {
    is AutomationAction.Tap -> "タップ"
    is AutomationAction.Swipe -> "スクロール"
    is AutomationAction.IfBlock -> "IF"
    is AutomationAction.BreakLoop -> "ループ終了"
    is AutomationAction.Wait -> "待機"
    is AutomationAction.JumpTo -> "番号へ"
}

fun AutomationAction.flowSummary(fromPath: String = ""): String = when (this) {
    is AutomationAction.Tap ->
        "(${x.roundToInt()}, ${y.roundToInt()})  → ${waitAfterMs}ms ±${waitJitterMs}"
    is AutomationAction.Swipe -> {
        val full = if (fullScroll) " 端まで" else ""
        "(${startX.roundToInt()}, ${startY.roundToInt()}) → " +
            "(${endX.roundToInt()}, ${endY.roundToInt()})  ${durationMs}ms$full"
    }
    is AutomationAction.IfBlock -> {
        val cond = conditions.firstOrNull()?.summary() ?: "条件なし"
        val extra = if (conditions.size > 1) " ほか${conditions.size - 1}" else ""
        "$operator $cond$extra  THEN ${thenActions.size} / ELSE ${elseActions.size}"
    }
    is AutomationAction.BreakLoop -> "到達したら繰り返しを終了"
    is AutomationAction.Wait -> "${durationMs}ms待つ ±${waitJitterMs}ms"
    is AutomationAction.JumpTo -> {
        val limit = if (maxTimes > 0) " 最大${maxTimes}回" else ""
        "${jumpTargetLabel(resolvedTargetPath(), fromPath)}$limit  → ${waitAfterMs}ms ±${waitJitterMs}"
    }
}

fun AutomationCondition.summary(): String = when (this) {
    is AutomationCondition.TextExists -> {
        val region = regionSummary(regionLeft, regionTop, regionRight, regionBottom)
        "文字「${query.ifBlank { "未設定" }}」$region"
    }
    is AutomationCondition.UiState -> {
        val region = regionSummary(regionLeft, regionTop, regionRight, regionBottom)
        "活性「${query.ifBlank { "未設定" }}」$region"
    }
    is AutomationCondition.PixelColor ->
        "色(${x}, ${y})"
}

private fun regionSummary(left: Int?, top: Int?, right: Int?, bottom: Int?): String {
    if (left == null && top == null && right == null && bottom == null) return ""
    val l = left ?: 0
    val t = top ?: 0
    val r = right?.toString() ?: "∞"
    val b = bottom?.toString() ?: "∞"
    return " 区域($l,$t–$r,$b)"
}
