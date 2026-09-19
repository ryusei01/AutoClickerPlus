package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.autoclickerplus.R
import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.BranchSide
import com.autoclickerplus.model.ConditionOperator
import com.autoclickerplus.model.RepeatMode
import com.autoclickerplus.model.TextMatchMode
import com.autoclickerplus.model.flowSummary
import com.autoclickerplus.model.flowTitle
import com.autoclickerplus.model.jumpTargetLabel
import kotlin.math.roundToInt

data class FloatingEditorCallbacks(
    val onAddTap: () -> Unit,
    val onAddSwipe: () -> Unit,
    val onAddIf: () -> Unit,
    val onAddBreak: () -> Unit,
    val onAddToBranch: (String, BranchSide, AutomationAction) -> Unit,
    val onReplace: (AutomationAction) -> Unit,
    val onAddCondition: (String, AutomationCondition) -> Unit,
    val onReplaceCondition: (String, AutomationCondition) -> Unit,
    val onRemoveCondition: (String, String) -> Unit,
    val onRemove: (String) -> Unit,
    val onMove: (String, Int) -> Unit,
    val onPickCoordinates: (String) -> Unit,
    val onPickColor: (String, String) -> Unit,
    val onRepeatMode: (RepeatMode) -> Unit,
    val onRepeatCount: (Int) -> Unit,
    val onClose: () -> Unit,
)

@SuppressLint("SetTextI18n")
class FloatingEditorOverlay(
    private val service: AccessibilityService,
    private val callbacks: FloatingEditorCallbacks,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var content: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var config = AutomationConfig()
    private val expandedDetailIds = mutableSetOf<String>()

    val isVisible: Boolean get() = root != null

    fun show(config: AutomationConfig) {
        this.config = config
        if (root != null) {
            render()
            return
        }

        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = roundedBackground(0xF2292730.toInt(), dp(18).toFloat())
        }
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(service).apply {
            text = "↕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(12), 0)
        })
        header.addView(TextView(service).apply {
            text = "フロー編集"
            textSize = 18f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        header.addView(iconButton(R.drawable.ic_collapse, "編集を閉じる") {
            callbacks.onClose()
        })
        panel.addView(header)

        val scroll = ScrollView(service).apply {
            isFillViewport = true
        }
        val body = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val metrics = service.resources.displayMetrics
        val windowParams = WindowManager.LayoutParams(
            minOf(dp(390), metrics.widthPixels - dp(16)),
            minOf((metrics.heightPixels * 0.78f).roundToInt(), dp(680)),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(70)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        makeDraggable(header.getChildAt(0), panel, windowParams)
        windowManager.addView(panel, windowParams)
        root = panel
        content = body
        params = windowParams
        render()
    }

    fun updateConfig(config: AutomationConfig) {
        this.config = config
        if (isVisible && root?.findFocus() !is EditText) render()
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        content = null
        params = null
    }

    private fun render() {
        val body = content ?: return
        body.removeAllViews()
        body.addView(addButtons())
        body.addView(repeatControls())

        if (config.actions.isEmpty()) {
            body.addView(label(service.getString(R.string.empty_actions)))
        } else {
            config.actions.forEachIndexed { index, action ->
                if (index > 0) body.addView(flowArrow())
                body.addView(actionEditor("${index + 1}", index, config.actions.size, action))
            }
        }
    }

    private fun addButtons() = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton(R.drawable.ic_tap, "タップ", callbacks.onAddTap), rowButtonParams())
            addView(actionButton(R.drawable.ic_swipe, "スクロール", callbacks.onAddSwipe), rowButtonParams())
        })
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton(R.drawable.ic_edit, "IF", callbacks.onAddIf), rowButtonParams())
            addView(actionButton(R.drawable.ic_break, "ループ終了", callbacks.onAddBreak), rowButtonParams())
        })
    }

    private fun rowButtonParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    private fun repeatControls() = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(label("繰り返し"))
        val group = RadioGroup(service).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val infinite = RadioButton(service).apply {
            text = service.getString(R.string.repeat_until_stop)
            setTextColor(Color.WHITE)
            isChecked = config.repeatMode == RepeatMode.INFINITE
            setOnClickListener { callbacks.onRepeatMode(RepeatMode.INFINITE) }
        }
        val count = RadioButton(service).apply {
            text = service.getString(R.string.repeat_count)
            setTextColor(Color.WHITE)
            isChecked = config.repeatMode == RepeatMode.COUNT
            setOnClickListener { callbacks.onRepeatMode(RepeatMode.COUNT) }
        }
        group.addView(infinite)
        group.addView(count)
        addView(group)
        if (config.repeatMode == RepeatMode.COUNT) {
            addView(numberField("繰り返し回数", config.repeatCount.toString()) { value ->
                value.toIntOrNull()?.let(callbacks.onRepeatCount)
            })
        }
    }

    private fun actionEditor(
        path: String,
        index: Int,
        siblingCount: Int,
        action: AutomationAction,
    ): View = when (action) {
        is AutomationAction.IfBlock -> ifEditor(path, index, siblingCount, action)
        is AutomationAction.BreakLoop -> breakEditor(path, index, siblingCount, action)
        else -> LinearLayout(service).apply {
            val expanded = action.id in expandedDetailIds
            val nodeColor = if (action is AutomationAction.Tap) {
                0xFF2E4A63.toInt()
            } else {
                0xFF2A4F48.toInt()
            }
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(nodeColor, dp(12).toFloat())
            addView(flowNodeHeader(path, action, expanded, index, siblingCount))
            if (expanded) {
                addView(LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(coordinateText(action)), LinearLayout.LayoutParams(0, dp(48), 1f))
                    addView(smallButton("修正", true) {
                        callbacks.onPickCoordinates(action.id)
                    })
                })
                addView(numberField(
                    "次の動作までの待機時間 ms（±30）",
                    action.waitAfterMs.toString(),
                ) { value ->
                    value.toLongOrNull()?.let { callbacks.onReplace(action.withWait(it)) }
                })
                if (action is AutomationAction.Swipe) {
                    addView(numberField("スクロール時間 ms", action.durationMs.toString()) { value ->
                        value.toLongOrNull()?.let {
                            callbacks.onReplace(action.copy(durationMs = it.coerceIn(100L, 2_000L)))
                        }
                    })
                    addView(smallButton(
                        if (action.stopAtEnd) "最後で止める: ON" else "最後で止める: OFF",
                        true,
                    ) {
                        callbacks.onReplace(action.copy(stopAtEnd = !action.stopAtEnd))
                    })
                }
                addView(LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label("位置の揺らぎ ±${action.jitterPx}px"),
                        LinearLayout.LayoutParams(0, dp(44), 1f))
                    addView(smallButton("−", action.jitterPx > 3) {
                        callbacks.onReplace(action.withJitter(action.jitterPx - 1))
                    })
                    addView(smallButton("+", action.jitterPx < 10) {
                        callbacks.onReplace(action.withJitter(action.jitterPx + 1))
                    })
                })
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(2), 0, dp(2)) }
        }
    }

    private fun breakEditor(
        path: String,
        index: Int,
        siblingCount: Int,
        action: AutomationAction.BreakLoop,
    ) = LinearLayout(service).apply {
        val expanded = action.id in expandedDetailIds
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = roundedBackground(0xFF5C4033.toInt(), dp(12).toFloat())
        addView(flowNodeHeader(path, action, expanded, index, siblingCount))
        if (expanded) {
            addView(label("この操作に到達すると繰り返しを終了します"))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, dp(2), 0, dp(2)) }
    }

    private fun ifEditor(
        path: String,
        index: Int,
        siblingCount: Int,
        block: AutomationAction.IfBlock,
    ) = LinearLayout(service).apply {
        val expanded = block.id in expandedDetailIds
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = roundedBackground(0xFF4A3A62.toInt(), dp(12).toFloat())
        addView(flowNodeHeader(path, block, expanded, index, siblingCount))
        if (expanded) {
        addView(smallButton(block.operator.name, true) {
            callbacks.onReplace(
                block.copy(
                    operator = if (block.operator == ConditionOperator.AND) {
                        ConditionOperator.OR
                    } else {
                        ConditionOperator.AND
                    },
                ),
            )
        })
        addView(numberField(
            "次の動作までの待機時間 ms（±30）",
            block.waitAfterMs.toString(),
        ) { value ->
            value.toLongOrNull()?.let { callbacks.onReplace(block.withWait(it)) }
        })
        addView(label("条件"))
        block.conditions.forEach { addView(conditionEditor(block.id, it)) }
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallButton("+文字", true) {
                callbacks.onAddCondition(block.id, AutomationCondition.TextExists())
            })
            addView(smallButton("+活性", true) {
                callbacks.onAddCondition(block.id, AutomationCondition.UiState())
            })
            addView(smallButton("+色", true) {
                callbacks.onAddCondition(block.id, AutomationCondition.PixelColor())
            })
        })
        addView(branchEditor("$path-T", "THEN", block, BranchSide.THEN))
        addView(branchEditor("$path-E", "ELSE", block, BranchSide.ELSE))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, dp(2), 0, dp(2)) }
    }

    private fun conditionEditor(
        blockId: String,
        condition: AutomationCondition,
    ) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), 0, dp(4))
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label(when (condition) {
                is AutomationCondition.TextExists -> "文字存在"
                is AutomationCondition.UiState -> "活性状態"
                is AutomationCondition.PixelColor -> "画面色"
            }), LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(smallButton("条件削除", true) {
                callbacks.onRemoveCondition(blockId, condition.id)
            })
        })
        when (condition) {
            is AutomationCondition.TextExists -> {
                addView(textField("検索文字", condition.query) {
                    callbacks.onReplaceCondition(blockId, condition.copy(query = it))
                })
                addView(smallButton(condition.matchMode.displayName, true) {
                    callbacks.onReplaceCondition(
                        blockId,
                        condition.copy(matchMode = condition.matchMode.toggled()),
                    )
                })
            }
            is AutomationCondition.UiState -> {
                addView(textField("対象文字", condition.query) {
                    callbacks.onReplaceCondition(blockId, condition.copy(query = it))
                })
                addView(LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(smallButton(condition.matchMode.displayName, true) {
                        callbacks.onReplaceCondition(
                            blockId,
                            condition.copy(matchMode = condition.matchMode.toggled()),
                        )
                    })
                    addView(smallButton("enabled:${condition.expectedEnabled.displayName}", true) {
                        callbacks.onReplaceCondition(
                            blockId,
                            condition.copy(
                                expectedEnabled = condition.expectedEnabled.nextExpected(),
                            ),
                        )
                    })
                    addView(smallButton("click:${condition.expectedClickable.displayName}", true) {
                        callbacks.onReplaceCondition(
                            blockId,
                            condition.copy(
                                expectedClickable = condition.expectedClickable.nextExpected(),
                            ),
                        )
                    })
                })
            }
            is AutomationCondition.PixelColor -> {
                addView(label(
                    "座標 (${condition.x}, ${condition.y})  色 " +
                        String.format("#%08X", condition.argb),
                ))
                addView(smallButton("画面から色を取得", true) {
                    callbacks.onPickColor(blockId, condition.id)
                })
                addView(numberField("色の許容差 0～255", condition.tolerance.toString()) {
                    it.toIntOrNull()?.let { value ->
                        callbacks.onReplaceCondition(
                            blockId,
                            condition.copy(tolerance = value.coerceIn(0, 255)),
                        )
                    }
                })
            }
        }
    }

    private fun branchEditor(
        pathPrefix: String,
        title: String,
        block: AutomationAction.IfBlock,
        side: BranchSide,
    ) = LinearLayout(service).apply {
        val actions = if (side == BranchSide.THEN) block.thenActions else block.elseActions
        val jumpTo = if (side == BranchSide.THEN) block.thenJumpTo else block.elseJumpTo
        val ownerNumber = pathPrefix.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(6), 0, dp(6))
        addView(label("◆ $title"))
        if (actions.isEmpty()) {
            addView(label("（空）"))
        }
        actions.forEachIndexed { index, action ->
            if (index > 0) addView(flowArrow())
            addView(actionEditor("$pathPrefix${index + 1}", index, actions.size, action))
        }
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallButton("+タップ", true) {
                callbacks.onAddToBranch(block.id, side, AutomationAction.Tap())
            })
            addView(smallButton("+スクロール", true) {
                callbacks.onAddToBranch(block.id, side, AutomationAction.Swipe())
            })
            addView(smallButton("+IF", true) {
                callbacks.onAddToBranch(block.id, side, AutomationAction.IfBlock())
            })
            addView(smallButton("+終了", true) {
                callbacks.onAddToBranch(block.id, side, AutomationAction.BreakLoop())
            })
        })
        addView(flowArrow())
        addView(label("→ ${jumpTargetLabel(jumpTo, ownerNumber)}"))
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(smallButton(if (jumpTo == null) "番号へ" else "次へ進む", true) {
                val updated = if (side == BranchSide.THEN) {
                    block.copy(thenJumpTo = if (jumpTo == null) ownerNumber else null)
                } else {
                    block.copy(elseJumpTo = if (jumpTo == null) ownerNumber else null)
                }
                callbacks.onReplace(updated)
            })
        })
        if (jumpTo != null) {
            addView(numberField("アクション番号", jumpTo.toString()) { value ->
                val number = value.toIntOrNull()?.takeIf { it >= 1 }
                callbacks.onReplace(
                    if (side == BranchSide.THEN) {
                        block.copy(thenJumpTo = number)
                    } else {
                        block.copy(elseJumpTo = number)
                    },
                )
            })
        }
        addView(label("今より小さい番号は戻り、大きい番号は先へ進みます"))
    }

    private fun flowNodeHeader(
        path: String,
        action: AutomationAction,
        expanded: Boolean,
        index: Int,
        siblingCount: Int,
    ) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("$path  ${action.flowTitle()}").apply {
                setOnClickListener { toggleExpanded(action.id) }
            }, LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(smallButton(if (expanded) "閉じる" else "詳細", true) {
                toggleExpanded(action.id)
            })
            addView(smallButton("↑", index > 0) { callbacks.onMove(action.id, -1) })
            addView(smallButton("↓", index < siblingCount - 1) {
                callbacks.onMove(action.id, 1)
            })
            addView(smallButton("削除", true) { callbacks.onRemove(action.id) })
        })
        addView(label(action.flowSummary(path.takeWhile { it.isDigit() }.toIntOrNull() ?: 0)).apply {
            setOnClickListener { toggleExpanded(action.id) }
        })
    }

    private fun toggleExpanded(id: String) {
        if (id in expandedDetailIds) {
            expandedDetailIds -= id
        } else {
            expandedDetailIds += id
        }
        render()
    }

    private fun flowArrow() = TextView(service).apply {
        text = "↓"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(0xFFB8C0D0.toInt())
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun textField(
        labelText: String,
        value: String,
        onCommit: (String) -> Unit,
    ) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(labelText))
        addView(commitField(value, InputType.TYPE_CLASS_TEXT, onCommit))
    }

    private fun numberField(
        labelText: String,
        value: String,
        onCommit: (String) -> Unit,
    ) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(labelText))
        addView(commitField(value, InputType.TYPE_CLASS_NUMBER, onCommit).apply {
            hint = "数値を入力"
            setHintTextColor(0xFFBBBBBB.toInt())
            setSelectAllOnFocus(true)
        })
    }

    private fun commitField(
        value: String,
        inputTypeValue: Int,
        onCommit: (String) -> Unit,
    ) = EditText(service).apply {
        setText(value)
        setTextColor(Color.WHITE)
        setSingleLine(true)
        inputType = inputTypeValue
        imeOptions = EditorInfo.IME_ACTION_DONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        )
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onCommit(text.toString())
                clearFocus()
                true
            } else {
                false
            }
        }
        setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                onCommit(text.toString())
            }
        }
    }

    private fun actionButton(icon: Int, text: String, onClick: () -> Unit) =
        Button(service).apply {
            this.text = text
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawablePadding = dp(6)
            setOnClickListener { onClick() }
        }

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit) =
        ImageButton(service).apply {
            setImageResource(icon)
            contentDescription = description
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    private fun smallButton(text: String, enabled: Boolean, onClick: () -> Unit) =
        Button(service).apply {
            this.text = text
            isEnabled = enabled
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44),
            )
        }

    private fun label(text: String) = TextView(service).apply {
        this.text = text
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun coordinateText(action: AutomationAction) = when (action) {
        is AutomationAction.Tap ->
            "(${action.x.roundToInt()}, ${action.y.roundToInt()})"
        is AutomationAction.Swipe ->
            "(${action.startX.roundToInt()}, ${action.startY.roundToInt()}) → " +
                "(${action.endX.roundToInt()}, ${action.endY.roundToInt()})"
        is AutomationAction.IfBlock -> "条件分岐"
        is AutomationAction.BreakLoop -> "ループ終了"
    }

    private fun makeDraggable(
        handle: View,
        target: View,
        windowParams: WindowManager.LayoutParams,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = windowParams.x
                    downY = windowParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    windowParams.x = downX + (event.rawX - downRawX).roundToInt()
                    windowParams.y = downY + (event.rawY - downRawY).roundToInt()
                    windowManager.updateViewLayout(target, windowParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handle.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun AutomationAction.withWait(value: Long): AutomationAction = when (this) {
        is AutomationAction.Tap -> copy(waitAfterMs = value.coerceAtLeast(0L))
        is AutomationAction.Swipe -> copy(waitAfterMs = value.coerceAtLeast(0L))
        is AutomationAction.IfBlock -> copy(waitAfterMs = value.coerceAtLeast(0L))
        is AutomationAction.BreakLoop -> this
    }

    private fun AutomationAction.withJitter(value: Int): AutomationAction = when (this) {
        is AutomationAction.Tap -> copy(jitterPx = value.coerceIn(3, 10))
        is AutomationAction.Swipe -> copy(jitterPx = value.coerceIn(3, 10))
        is AutomationAction.IfBlock -> this
        is AutomationAction.BreakLoop -> this
    }

    private fun TextMatchMode.toggled() =
        if (this == TextMatchMode.EXACT) TextMatchMode.CONTAINS else TextMatchMode.EXACT

    private val TextMatchMode.displayName: String
        get() = if (this == TextMatchMode.EXACT) "完全一致" else "部分一致"

    private fun Boolean?.nextExpected(): Boolean? = when (this) {
        null -> true
        true -> false
        false -> null
    }

    private val Boolean?.displayName: String
        get() = when (this) {
            null -> "任意"
            true -> "true"
            false -> "false"
        }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).roundToInt()
}
