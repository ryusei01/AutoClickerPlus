package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoclickerplus.engine.RunnerState
import com.autoclickerplus.model.AutomationAction
import kotlin.math.roundToInt

class OverlayController(
    private val service: AccessibilityService,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var controls: View? = null
    private var picker: View? = null
    private var statusLabel: TextView? = null
    private var runnerState = RunnerState.IDLE

    fun showControls() {
        if (controls != null || picker != null) return
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedBackground(0xEE25232AL.toInt(), dp(16).toFloat())
        }
        val dragHandle = TextView(service).apply {
            text = "↕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        val status = TextView(service).apply {
            text = runnerState.label
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(8), 0)
        }
        val start = Button(service).apply {
            text = "START"
            setOnClickListener { onStart() }
        }
        val stop = Button(service).apply {
            text = "STOP"
            setOnClickListener { onStop() }
        }
        panel.addView(dragHandle)
        panel.addView(status)
        panel.addView(start)
        panel.addView(stop)

        val params = overlayParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(100)
        }
        makeDraggable(dragHandle, panel, params)
        windowManager.addView(panel, params)
        controls = panel
        statusLabel = status
    }

    fun showPicker(action: AutomationAction, onDone: (AutomationAction) -> Unit) {
        removeControls()
        removePicker()

        val root = FrameLayout(service).apply {
            setBackgroundColor(0x22000000)
        }
        val markerSize = dp(52)
        val markers = when (action) {
            is AutomationAction.Tap -> listOf(
                marker("T", Color.rgb(211, 47, 47), action.x, action.y, markerSize),
            )
            is AutomationAction.Swipe -> listOf(
                marker("S", Color.rgb(25, 118, 210), action.startX, action.startY, markerSize),
                marker("E", Color.rgb(46, 125, 50), action.endX, action.endY, markerSize),
            )
        }
        markers.forEach(root::addView)

        val done = Button(service).apply {
            text = "この位置で決定"
            setOnClickListener {
                val result = when (action) {
                    is AutomationAction.Tap -> action.copy(
                        x = markers[0].centerX(),
                        y = markers[0].centerY(),
                    )
                    is AutomationAction.Swipe -> action.copy(
                        startX = markers[0].centerX(),
                        startY = markers[0].centerY(),
                        endX = markers[1].centerX(),
                        endY = markers[1].centerY(),
                    )
                }
                removePicker()
                showControls()
                onDone(result)
            }
        }
        root.addView(
            done,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(24) },
        )

        val params = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )
        windowManager.addView(root, params)
        picker = root
    }

    fun removeAll() {
        removePicker()
        removeControls()
    }

    fun updateRunnerState(state: RunnerState) {
        runnerState = state
        statusLabel?.text = state.label
    }

    private fun marker(
        label: String,
        color: Int,
        centerX: Float,
        centerY: Float,
        size: Int,
    ): TextView = TextView(service).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = roundedBackground(color, size / 2f)
        x = centerX - size / 2f
        y = centerY - size / 2f
        layoutParams = FrameLayout.LayoutParams(size, size)
        setOnTouchListener(object : View.OnTouchListener {
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchX = event.rawX - view.x
                        touchY = event.rawY - view.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val parentView = view.parent as View
                        view.x = (event.rawX - touchX).coerceIn(0f, (parentView.width - view.width).toFloat())
                        view.y = (event.rawY - touchY).coerceIn(0f, (parentView.height - view.height).toFloat())
                        return true
                    }
                }
                return false
            }
        })
    }

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
                    windowManager.updateViewLayout(target, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
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

    private fun TextView.centerX() = x + width / 2f
    private fun TextView.centerY() = y + height / 2f
    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).roundToInt()

    private fun removeControls() {
        controls?.let { runCatching { windowManager.removeView(it) } }
        controls = null
        statusLabel = null
    }

    private fun removePicker() {
        picker?.let { runCatching { windowManager.removeView(it) } }
        picker = null
    }

    private val RunnerState.label: String
        get() = when (this) {
            RunnerState.IDLE -> "待機"
            RunnerState.RUNNING -> "実行中"
            RunnerState.FAILED -> "失敗"
        }
}
