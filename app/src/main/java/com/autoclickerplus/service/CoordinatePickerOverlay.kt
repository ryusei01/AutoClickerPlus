package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.autoclickerplus.R
import com.autoclickerplus.model.AutomationAction
import kotlin.math.roundToInt

class CoordinatePickerOverlay(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val markerWindows = mutableListOf<MarkerWindow>()
    private var controlBar: View? = null
    private var lineView: SwipeLineView? = null
    private var action: AutomationAction? = null
    private var sequenceNumber: String = ""

    val isVisible: Boolean get() = markerWindows.isNotEmpty() || controlBar != null

    fun show(
        action: AutomationAction,
        sequenceNumber: String,
        onDone: (AutomationAction) -> Unit,
        onCancel: () -> Unit,
    ) {
        hide()
        this.action = action
        this.sequenceNumber = sequenceNumber

        when (action) {
            is AutomationAction.Tap -> {
                markerWindows += addMarker(
                    label = sequenceNumber,
                    color = Color.rgb(211, 47, 47),
                    centerX = action.x,
                    centerY = action.y,
                )
            }
            is AutomationAction.Swipe -> {
                markerWindows += addMarker(
                    label = "${sequenceNumber}S",
                    color = Color.rgb(25, 118, 210),
                    centerX = action.startX,
                    centerY = action.startY,
                )
                markerWindows += addMarker(
                    label = "${sequenceNumber}E",
                    color = Color.rgb(46, 125, 50),
                    centerX = action.endX,
                    centerY = action.endY,
                )
                showSwipeLine()
            }
            is AutomationAction.IfBlock, is AutomationAction.BreakLoop,
            is AutomationAction.Wait, is AutomationAction.JumpTo -> return
        }
        showControlBar(
            title = service.getString(R.string.picker_title, sequenceNumber),
            onDone = {
                val updated = updatedAction() ?: return@showControlBar
                hide()
                onDone(updated)
            },
            onCancel = onCancel,
        )
        updateLine()
    }

    fun showColor(
        initialX: Int,
        initialY: Int,
        onDone: (Int, Int) -> Unit,
        onCancel: () -> Unit,
    ) {
        hide()
        markerWindows += addMarker(
            label = "色",
            color = Color.rgb(123, 31, 162),
            centerX = initialX.toFloat(),
            centerY = initialY.toFloat(),
        )
        showControlBar(
            title = service.getString(R.string.color_picker_title),
            onDone = {
                val marker = markerWindows.firstOrNull() ?: return@showControlBar
                val x = marker.centerX.roundToInt()
                val y = marker.centerY.roundToInt()
                hide()
                onDone(x, y)
            },
            onCancel = onCancel,
        )
    }

    fun hide() {
        markerWindows.forEach { runCatching { windowManager.removeView(it.view) } }
        markerWindows.clear()
        controlBar?.let { runCatching { windowManager.removeView(it) } }
        controlBar = null
        lineView?.let { runCatching { windowManager.removeView(it) } }
        lineView = null
        action = null
        sequenceNumber = ""
    }

    private fun addMarker(
        label: String,
        color: Int,
        centerX: Float,
        centerY: Float,
    ): MarkerWindow {
        val size = dp(54)
        val metrics = service.resources.displayMetrics
        val marker = TextView(service).apply {
            text = label
            textSize = if (label.length > 1) 15f else 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBackground(color, size / 2f)
            contentDescription = "アクション${label}の位置"
        }
        val params = overlayParams(
            width = size,
            height = size,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (centerX - size / 2f).roundToInt().coerceIn(0, metrics.widthPixels - size)
            y = (centerY - size / 2f).roundToInt().coerceIn(0, metrics.heightPixels - size)
        }
        makeMarkerDraggable(marker, params, size)
        windowManager.addView(marker, params)
        return MarkerWindow(marker, params, size)
    }

    private fun showControlBar(
        title: String,
        onDone: () -> Unit,
        onCancel: () -> Unit,
    ) {
        val bar = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBackground(0xF2292730.toInt(), dp(16).toFloat())
        }
        val dragHandle = TextView(service).apply {
            text = "↕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = "位置指定パネルを移動"
        }
        bar.addView(dragHandle)
        bar.addView(TextView(service).apply {
            text = title
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
        })
        bar.addView(Button(service).apply {
            text = "キャンセル"
            setOnClickListener {
                hide()
                onCancel()
            }
        })
        bar.addView(Button(service).apply {
            text = "決定"
            setOnClickListener { onDone() }
        })
        val params = overlayParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        makeControlBarDraggable(dragHandle, bar, params)
        windowManager.addView(bar, params)
        controlBar = bar
    }

    private fun showSwipeLine() {
        val view = SwipeLineView(service)
        val params = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )
        windowManager.addView(view, params)
        lineView = view
    }

    private fun makeMarkerDraggable(
        marker: View,
        params: WindowManager.LayoutParams,
        size: Int,
    ) {
        val metrics = service.resources.displayMetrics
        var offsetX = 0f
        var offsetY = 0f
        marker.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    offsetX = event.rawX - params.x
                    offsetY = event.rawY - params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (event.rawX - offsetX).roundToInt()
                        .coerceIn(0, metrics.widthPixels - size)
                    params.y = (event.rawY - offsetY).roundToInt()
                        .coerceIn(0, metrics.heightPixels - size)
                    windowManager.updateViewLayout(marker, params)
                    updateLine()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    marker.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun makeControlBarDraggable(
        handle: View,
        bar: View,
        params: WindowManager.LayoutParams,
    ) {
        val metrics = service.resources.displayMetrics
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (params.gravity != (Gravity.TOP or Gravity.START)) {
                        params.gravity = Gravity.TOP or Gravity.START
                        params.x = (event.rawX - event.x - handle.left).roundToInt()
                        params.y = (event.rawY - event.y - handle.top).roundToInt()
                        windowManager.updateViewLayout(bar, params)
                    }
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (downX + (event.rawX - downRawX).roundToInt())
                        .coerceIn(0, (metrics.widthPixels - bar.width).coerceAtLeast(0))
                    params.y = (downY + (event.rawY - downRawY).roundToInt())
                        .coerceIn(0, (metrics.heightPixels - bar.height).coerceAtLeast(0))
                    windowManager.updateViewLayout(bar, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handle.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun updatedAction(): AutomationAction? {
        val original = action ?: return null
        return when (original) {
            is AutomationAction.Tap -> original.copy(
                x = markerWindows[0].centerX,
                y = markerWindows[0].centerY,
            )
            is AutomationAction.Swipe -> original.copy(
                startX = markerWindows[0].centerX,
                startY = markerWindows[0].centerY,
                endX = markerWindows[1].centerX,
                endY = markerWindows[1].centerY,
            )
            is AutomationAction.IfBlock, is AutomationAction.BreakLoop,
            is AutomationAction.Wait, is AutomationAction.JumpTo -> null
        }
    }

    private fun updateLine() {
        if (markerWindows.size != 2) return
        lineView?.setPoints(
            markerWindows[0].centerX,
            markerWindows[0].centerY,
            markerWindows[1].centerX,
            markerWindows[1].centerY,
        )
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

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).roundToInt()

    private data class MarkerWindow(
        val view: View,
        val params: WindowManager.LayoutParams,
        val size: Int,
    ) {
        val centerX: Float get() = params.x + size / 2f
        val centerY: Float get() = params.y + size / 2f
    }

    private class SwipeLineView(service: AccessibilityService) : View(service) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 255, 193, 7)
            strokeWidth = service.resources.displayMetrics.density * 4f
            style = Paint.Style.STROKE
        }
        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var endY = 0f

        fun setPoints(startX: Float, startY: Float, endX: Float, endY: Float) {
            this.startX = startX
            this.startY = startY
            this.endX = endX
            this.endY = endY
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawLine(startX, startY, endX, endY, paint)
            val angle = kotlin.math.atan2(endY - startY, endX - startX)
            val arrow = resources.displayMetrics.density * 14f
            canvas.drawLine(
                endX,
                endY,
                endX - arrow * kotlin.math.cos(angle - 0.55f),
                endY - arrow * kotlin.math.sin(angle - 0.55f),
                paint,
            )
            canvas.drawLine(
                endX,
                endY,
                endX - arrow * kotlin.math.cos(angle + 0.55f),
                endY - arrow * kotlin.math.sin(angle + 0.55f),
                paint,
            )
        }
    }
}
