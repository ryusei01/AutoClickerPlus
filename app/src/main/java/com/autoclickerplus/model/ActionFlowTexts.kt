package com.autoclickerplus.model

import kotlin.math.roundToInt

fun AutomationAction.flowTitle(): String = when (this) {
    is AutomationAction.Tap -> "タップ"
    is AutomationAction.Swipe -> "スクロール"
    is AutomationAction.IfBlock -> "IF"
    is AutomationAction.BreakLoop -> "ループ終了"
    is AutomationAction.Wait -> "待機"
}

fun AutomationAction.flowSummary(fromNumber: Int = 0): String = when (this) {
    is AutomationAction.Tap ->
        "(${x.roundToInt()}, ${y.roundToInt()})  → ${waitAfterMs}ms ±${waitJitterMs}"
    is AutomationAction.Swipe ->
        "(${startX.roundToInt()}, ${startY.roundToInt()}) → " +
            "(${endX.roundToInt()}, ${endY.roundToInt()})  ${durationMs}ms"
    is AutomationAction.IfBlock -> {
        val cond = conditions.firstOrNull()?.summary() ?: "条件なし"
        val extra = if (conditions.size > 1) " ほか${conditions.size - 1}" else ""
        "$operator $cond$extra  THEN ${thenActions.size}→${jumpTargetLabel(thenJumpTo, fromNumber)} ${thenWaitMs}ms±${thenWaitJitterMs} / " +
            "ELSE ${elseActions.size}→${jumpTargetLabel(elseJumpTo, fromNumber)} ${elseWaitMs}ms±${elseWaitJitterMs}"
    }
    is AutomationAction.BreakLoop -> "到達したら繰り返しを終了"
    is AutomationAction.Wait -> "${formatWaitSeconds(durationMs)}秒待つ ±${waitJitterMs}ms"
}

fun AutomationCondition.summary(): String = when (this) {
    is AutomationCondition.TextExists ->
        "文字「${query.ifBlank { "未設定" }}」"
    is AutomationCondition.UiState ->
        "活性「${query.ifBlank { "未設定" }}」"
    is AutomationCondition.PixelColor ->
        "色(${x}, ${y})"
}
