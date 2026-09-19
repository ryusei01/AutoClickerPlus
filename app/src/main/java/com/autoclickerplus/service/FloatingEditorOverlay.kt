package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
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
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.RepeatMode
import kotlin.math.roundToInt

data class FloatingEditorCallbacks(
    val onAddTap: () -> Unit,
    val onAddSwipe: () -> Unit,
    val onReplace: (AutomationAction) -> Unit,
    val onRemove: (String) -> Unit,
    val onMove: (String, Int) -> Unit,
    val onPickCoordinates: (String) -> Unit,
    val onRepeatMode: (RepeatMode) -> Unit,
    val onRepeatCount: (Int) -> Unit,
    val onClose: () -> Unit,
)

class FloatingEditorOverlay(
    private val service: AccessibilityService,
    private val callbacks: FloatingEditorCallbacks,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var content: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var config = AutomationConfig()

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
            text = "アクション編集"
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
                body.addView(actionEditor(index, action))
            }
        }
    }

    private fun addButtons() = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(actionButton(R.drawable.ic_tap, "タップ追加", callbacks.onAddTap))
        addView(actionButton(R.drawable.ic_swipe, "スクロール追加", callbacks.onAddSwipe))
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

    private fun actionEditor(index: Int, action: AutomationAction) =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(0xFF403D48.toInt(), dp(12).toFloat())
            val type = if (action is AutomationAction.Tap) "タップ" else "スクロール"
            addView(LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("${index + 1}. $type"), LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(smallButton("↑", index > 0) { callbacks.onMove(action.id, -1) })
                addView(smallButton("↓", index < config.actions.lastIndex) {
                    callbacks.onMove(action.id, 1)
                })
                addView(smallButton("削除", true) { callbacks.onRemove(action.id) })
            })
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(4), 0, dp(6)) }
        }

    private fun numberField(
        labelText: String,
        value: String,
        onCommit: (String) -> Unit,
    ) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(labelText))
        val inputRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val field = EditText(service).apply {
            setText(value)
            hint = "数値を入力"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFBBBBBB.toInt())
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    onCommit(text.toString())
                    clearFocus()
                    true
                } else {
                    false
                }
            }
        }
        inputRow.addView(field, LinearLayout.LayoutParams(0, dp(52), 1f))
        inputRow.addView(smallButton("保存", true) {
            onCommit(field.text.toString())
            field.clearFocus()
        })
        addView(inputRow)
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
    }

    private fun AutomationAction.withJitter(value: Int): AutomationAction = when (this) {
        is AutomationAction.Tap -> copy(jitterPx = value.coerceIn(3, 10))
        is AutomationAction.Swipe -> copy(jitterPx = value.coerceIn(3, 10))
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).roundToInt()
}
