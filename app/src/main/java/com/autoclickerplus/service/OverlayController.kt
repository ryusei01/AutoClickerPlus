package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.autoclickerplus.R
import com.autoclickerplus.engine.RunnerState
import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.AutomationConfigEditor
import com.autoclickerplus.model.BranchSide
import com.autoclickerplus.model.RepeatMode
import kotlin.math.roundToInt

data class OverlayCallbacks(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onAddTap: () -> Unit,
    val onAddSwipe: () -> Unit,
    val onAddIf: () -> Unit,
    val onAddBreak: () -> Unit,
    val onAddWait: () -> Unit,
    val onAddJumpTo: () -> Unit,
    val onAddToBranch: (String, BranchSide, AutomationAction) -> Unit,
    val onReplace: (AutomationAction) -> Unit,
    val onAddCondition: (String, AutomationCondition) -> Unit,
    val onReplaceCondition: (String, AutomationCondition) -> Unit,
    val onRemoveCondition: (String, String) -> Unit,
    val onPickColor: (String, String) -> Unit,
    val onRemove: (String) -> Unit,
    val onMove: (String, Int) -> Unit,
    val onRepeatMode: (RepeatMode) -> Unit,
    val onRepeatCount: (Int) -> Unit,
    val onReplaceConfig: (AutomationConfig) -> Unit,
)

class OverlayController(
    private val service: AccessibilityService,
    private val callbacks: OverlayCallbacks,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val picker = CoordinatePickerOverlay(service)
    private lateinit var editor: FloatingEditorOverlay

    private var controls: View? = null
    private var statusLabel: TextView? = null
    private var runnerState = RunnerState.IDLE
    private var config = AutomationConfig()
    private var toolbarX = dp(12)
    private var toolbarY = dp(100)
    private var collapsed = false

    init {
        editor = FloatingEditorOverlay(
            service,
            FloatingEditorCallbacks(
                onAddTap = callbacks.onAddTap,
                onAddSwipe = callbacks.onAddSwipe,
                onAddIf = callbacks.onAddIf,
                onAddBreak = callbacks.onAddBreak,
                onAddWait = callbacks.onAddWait,
                onAddJumpTo = callbacks.onAddJumpTo,
                onAddToBranch = callbacks.onAddToBranch,
                onReplace = callbacks.onReplace,
                onAddCondition = callbacks.onAddCondition,
                onReplaceCondition = callbacks.onReplaceCondition,
                onRemoveCondition = callbacks.onRemoveCondition,
                onRemove = callbacks.onRemove,
                onMove = callbacks.onMove,
                onPickCoordinates = ::showPicker,
                onPickAllCoordinates = ::showAllPicker,
                onPickColor = callbacks.onPickColor,
                onRepeatMode = callbacks.onRepeatMode,
                onRepeatCount = callbacks.onRepeatCount,
                onClose = {
                    editor.hide()
                    showControls()
                },
            ),
        )
    }

    fun showControls() {
        if (controls != null || editor.isVisible || picker.isVisible) return

        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = roundedBackground(0xF2292730.toInt(), dp(16).toFloat())
        }
        val dragHandle = TextView(service).apply {
            text = "↕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = "パネルを移動"
        }
        val actions = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        val status = TextView(service).apply {
            text = runnerState.label
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, dp(6), 0)
        }
        actions.addView(status)
        actions.addView(iconButton(R.drawable.ic_play, "開始", callbacks.onStart))
        actions.addView(iconButton(R.drawable.ic_stop, "停止", callbacks.onStop))
        actions.addView(iconButton(R.drawable.ic_tap, "タップを追加", callbacks.onAddTap))
        actions.addView(iconButton(R.drawable.ic_swipe, "スクロールを追加", callbacks.onAddSwipe))
        actions.addView(iconButton(R.drawable.ic_wait, "待機を追加", callbacks.onAddWait))
        actions.addView(iconButton(R.drawable.ic_break, "ループ終了を追加", callbacks.onAddBreak))
        actions.addView(iconButton(R.drawable.ic_edit, "アクションを編集") {
            removeControls()
            editor.show(config)
        })
        actions.addView(iconButton(R.drawable.ic_close, "フローティングを終了") {
            callbacks.onStop()
            removeAll()
        })
        val collapse = viewIconButton(
            R.drawable.ic_collapse,
            if (collapsed) "パネルを展開" else "パネルを最小化",
        ) {
            collapsed = !collapsed
            actions.visibility = if (collapsed) View.GONE else View.VISIBLE
            (it as ImageButton).rotation = if (collapsed) 180f else 0f
            it.contentDescription = if (collapsed) "パネルを展開" else "パネルを最小化"
        }.apply {
            rotation = if (collapsed) 180f else 0f
        }
        panel.addView(dragHandle)
        panel.addView(actions)
        panel.addView(collapse)

        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = toolbarX
            y = toolbarY
        }
        makeDraggable(dragHandle, panel, params)
        windowManager.addView(panel, params)
        controls = panel
        statusLabel = status
    }

    fun showAllPicker() {
        val pickables = AutomationConfigEditor.collectPickableActions(config)
        if (pickables.isEmpty()) {
            android.widget.Toast.makeText(
                service,
                service.getString(R.string.bulk_picker_empty),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            editor.show(config)
            return
        }
        removeControls()
        editor.hide()
        picker.showAll(
            config = config,
            onDone = { updated ->
                callbacks.onReplaceConfig(updated)
                config = updated
                editor.show(config)
            },
            onCancel = { editor.show(config) },
        )
    }

    fun showPicker(actionId: String, removeOnCancel: Boolean = false) {
        val action = AutomationConfigEditor.findAction(config, actionId) ?: return
        if (
            action is AutomationAction.IfBlock ||
            action is AutomationAction.BreakLoop ||
            action is AutomationAction.Wait ||
            action is AutomationAction.JumpTo
        ) return
        val sequence = AutomationConfigEditor.sequencePath(config, actionId) ?: "?"
        removeControls()
        editor.hide()
        picker.show(
            action = action,
            sequenceNumber = sequence,
            onDone = { updated ->
                callbacks.onReplace(updated)
                config = AutomationConfigEditor.replace(config, updated)
                editor.show(config)
            },
            onCancel = {
                if (removeOnCancel) {
                    callbacks.onRemove(actionId)
                    config = AutomationConfigEditor.remove(config, actionId)
                }
                editor.show(config)
            },
        )
    }

    fun showColorPicker(
        initialX: Int,
        initialY: Int,
        onDone: (Int, Int) -> Unit,
    ) {
        removeControls()
        editor.hide()
        picker.showColor(
            initialX = initialX,
            initialY = initialY,
            onDone = { x, y ->
                onDone(x, y)
            },
            onCancel = { editor.show(config) },
        )
    }

    fun showEditor() {
        removeControls()
        editor.show(config)
    }

    fun updateConfig(config: AutomationConfig) {
        this.config = config
        editor.updateConfig(config)
    }

    fun updateRunnerState(state: RunnerState) {
        runnerState = state
        statusLabel?.text = state.label
    }

    fun hideTransientOverlays() {
        picker.hide()
        editor.hide()
    }

    fun removeAll() {
        picker.hide()
        editor.hide()
        removeControls()
    }

    private fun viewIconButton(
        icon: Int,
        description: String,
        onClick: (View) -> Unit,
    ) = ImageButton(service).apply {
        setImageResource(icon)
        contentDescription = description
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(7), dp(7), dp(7), dp(7))
        setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
    }

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit) =
        viewIconButton(icon, description) { onClick() }

    private fun makeDraggable(
        handle: View,
        target: View,
        params: WindowManager.LayoutParams,
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
                    downX = params.x
                    downY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = downX + (event.rawX - downRawX).roundToInt()
                    params.y = downY + (event.rawY - downRawY).roundToInt()
                    toolbarX = params.x
                    toolbarY = params.y
                    windowManager.updateViewLayout(target, params)
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

    private fun overlayParams(width: Int, height: Int, flags: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun removeControls() {
        controls?.let { runCatching { windowManager.removeView(it) } }
        controls = null
        statusLabel = null
    }

    private val RunnerState.label: String
        get() = when (this) {
            RunnerState.IDLE -> "待機"
            RunnerState.RUNNING -> "実行中"
            RunnerState.FAILED -> "失敗"
        }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).roundToInt()
}
