package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.autoclickerplus.R
import com.autoclickerplus.engine.ScreenBounds
import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.AutomationConfigEditor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class CoordinatePickerOverlay(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val markerWindows = mutableListOf<MarkerWindow>()
    private var bulkMarkers = mutableListOf<BulkMarker>()
    private var controlBar: View? = null
    private var lineView: SwipeLineView? = null
    private var bulkLineView: BulkSwipeLinesView? = null
    private var regionView: RegionRectView? = null
    private var action: AutomationAction? = null
    private var sequenceNumber: String = ""
    private var bulkConfig: AutomationConfig? = null
    private var bulkOnDone: ((AutomationConfig) -> Unit)? = null
    private var bulkOnCancel: (() -> Unit)? = null

    private var colorPreviewSwatch: View? = null
    private var colorPreviewLabel: TextView? = null
    private var colorSampleRequest: ((Int, Int, (Result<Int>) -> Unit) -> Unit)? = null
    private var lastColorSample: Int? = null
    private var lastColorSampleX: Int = Int.MIN_VALUE
    private var lastColorSampleY: Int = Int.MIN_VALUE

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
                    onMoved = ::updateLine,
                )
            }
            is AutomationAction.Swipe -> {
                markerWindows += addMarker(
                    label = "${sequenceNumber}S",
                    color = Color.rgb(25, 118, 210),
                    centerX = action.startX,
                    centerY = action.startY,
                    onMoved = ::updateLine,
                )
                markerWindows += addMarker(
                    label = "${sequenceNumber}E",
                    color = Color.rgb(46, 125, 50),
                    centerX = action.endX,
                    centerY = action.endY,
                    onMoved = ::updateLine,
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
            onCancel = {
                hide()
                onCancel()
            },
        )
        updateLine()
    }

    fun showAll(
        config: AutomationConfig,
        onDone: (AutomationConfig) -> Unit,
        onCancel: () -> Unit,
    ) {
        hide()
        val pickables = AutomationConfigEditor.collectPickableActions(config)
        if (pickables.isEmpty()) {
            onCancel()
            return
        }
        bulkConfig = config
        bulkOnDone = onDone
        bulkOnCancel = onCancel

        pickables.forEach { pickable ->
            when (val pickableAction = pickable.action) {
                is AutomationAction.Tap -> {
                    val marker = addMarker(
                        label = pickable.path,
                        color = Color.rgb(211, 47, 47),
                        centerX = pickableAction.x,
                        centerY = pickableAction.y,
                        onMoved = ::updateBulkLines,
                    )
                    markerWindows += marker
                    bulkMarkers += BulkMarker(
                        actionId = pickableAction.id,
                        role = BulkMarkerRole.TAP,
                        window = marker,
                    )
                }
                is AutomationAction.Swipe -> {
                    val start = addMarker(
                        label = "${pickable.path}S",
                        color = Color.rgb(25, 118, 210),
                        centerX = pickableAction.startX,
                        centerY = pickableAction.startY,
                        onMoved = ::updateBulkLines,
                    )
                    val end = addMarker(
                        label = "${pickable.path}E",
                        color = Color.rgb(46, 125, 50),
                        centerX = pickableAction.endX,
                        centerY = pickableAction.endY,
                        onMoved = ::updateBulkLines,
                    )
                    markerWindows += start
                    markerWindows += end
                    bulkMarkers += BulkMarker(pickableAction.id, BulkMarkerRole.SWIPE_START, start)
                    bulkMarkers += BulkMarker(pickableAction.id, BulkMarkerRole.SWIPE_END, end)
                }
                else -> Unit
            }
        }
        showBulkSwipeLines()
        showControlBar(
            title = service.getString(R.string.bulk_picker_title),
            onDone = {
                val updated = buildUpdatedConfig() ?: return@showControlBar
                hide()
                onDone(updated)
            },
            onCancel = {
                val cancel = bulkOnCancel
                hide()
                cancel?.invoke()
            },
        )
        updateBulkLines()
    }

    fun showColor(
        initialX: Int,
        initialY: Int,
        onSample: (x: Int, y: Int, done: (Result<Int>) -> Unit) -> Unit,
        onDone: (x: Int, y: Int, color: Int) -> Unit,
        onCancel: () -> Unit,
    ) {
        hide()
        colorSampleRequest = onSample
        val screen = service.overlayScreenBounds()
        val startX = if (initialX <= 0 && initialY <= 0) screen.width / 2f else initialX.toFloat()
        val startY = if (initialX <= 0 && initialY <= 0) screen.height / 2f else initialY.toFloat()
        markerWindows += addColorCrosshair(
            centerX = startX,
            centerY = startY,
            onMoved = { lastColorSample = null },
        )
        showColorControlBar(
            onCheck = { sampleColorAtMarker { /* preview only */ } },
            onDone = {
                val marker = markerWindows.firstOrNull() ?: return@showColorControlBar
                val x = marker.centerX.roundToInt()
                val y = marker.centerY.roundToInt()
                val cached = lastColorSample
                    ?.takeIf { lastColorSampleX == x && lastColorSampleY == y }
                if (cached != null) {
                    hide()
                    onDone(x, y, cached)
                    return@showColorControlBar
                }
                sampleColorAtMarker { result ->
                    result.onSuccess { color ->
                        hide()
                        onDone(x, y, color)
                    }.onFailure {
                        // keep picker open; preview label already shows error
                    }
                }
            },
            onCancel = {
                hide()
                onCancel()
            },
        )
        // 開いた直後にも一回プレビュー
        markerWindows.firstOrNull()?.view?.post {
            sampleColorAtMarker { }
        }
    }

    fun showRegion(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        onDone: (Int, Int, Int, Int) -> Unit,
        onCancel: () -> Unit,
    ) {
        hide()
        markerWindows += addMarker(
            label = "左上",
            color = Color.rgb(106, 76, 147),
            centerX = left.toFloat(),
            centerY = top.toFloat(),
            onMoved = ::updateRegion,
        )
        markerWindows += addMarker(
            label = "右下",
            color = Color.rgb(186, 104, 200),
            centerX = right.toFloat(),
            centerY = bottom.toFloat(),
            onMoved = ::updateRegion,
        )
        showRegionRect()
        showControlBar(
            title = service.getString(R.string.region_picker_title),
            onDone = {
                if (markerWindows.size < 2) return@showControlBar
                val first = markerWindows[0]
                val second = markerWindows[1]
                val regionLeft = minOf(first.centerX, second.centerX).roundToInt()
                val regionTop = minOf(first.centerY, second.centerY).roundToInt()
                val regionRight = maxOf(first.centerX, second.centerX).roundToInt()
                val regionBottom = maxOf(first.centerY, second.centerY).roundToInt()
                hide()
                onDone(regionLeft, regionTop, regionRight, regionBottom)
            },
            onCancel = {
                hide()
                onCancel()
            },
        )
        updateRegion()
    }

    fun hide() {
        markerWindows.forEach { runCatching { windowManager.removeView(it.view) } }
        markerWindows.clear()
        bulkMarkers.clear()
        controlBar?.let { runCatching { windowManager.removeView(it) } }
        controlBar = null
        lineView?.let { runCatching { windowManager.removeView(it) } }
        lineView = null
        bulkLineView?.let { runCatching { windowManager.removeView(it) } }
        bulkLineView = null
        regionView?.let { runCatching { windowManager.removeView(it) } }
        regionView = null
        action = null
        sequenceNumber = ""
        bulkConfig = null
        bulkOnDone = null
        bulkOnCancel = null
        colorPreviewSwatch = null
        colorPreviewLabel = null
        colorSampleRequest = null
        lastColorSample = null
        lastColorSampleX = Int.MIN_VALUE
        lastColorSampleY = Int.MIN_VALUE
    }

    private fun addColorCrosshair(
        centerX: Float,
        centerY: Float,
        onMoved: (() -> Unit)? = null,
    ): MarkerWindow {
        val size = dp(72)
        val screen = service.overlayScreenBounds()
        val marker = ColorCrosshairView(service)
        val params = overlayParams(
            width = size,
            height = size,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            placeMarkerCenter(this, size, centerX, centerY, screen)
        }
        makeMarkerDraggable(marker, params, size) {
            onMoved?.invoke()
        }
        windowManager.addView(marker, params)
        return MarkerWindow(marker, params, size)
    }

    private fun showColorControlBar(
        onCheck: () -> Unit,
        onDone: () -> Unit,
        onCancel: () -> Unit,
    ) {
        val screen = service.overlayScreenBounds()
        val maxWidth = screen.width - dp(16)
        val bar = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(0xFF1E1C24.toInt(), dp(16).toFloat())
            minimumWidth = dp(240)
        }
        val dragHandle = TextView(service).apply {
            text = "↕"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
            contentDescription = "位置指定パネルを移動"
        }
        val titleView = TextView(service).apply {
            text = service.getString(R.string.color_picker_title)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val previewRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val swatch = View(service).apply {
            background = roundedBackground(0xFF555555.toInt(), dp(8).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                setMargins(0, 0, dp(10), 0)
            }
        }
        val previewLabel = TextView(service).apply {
            text = service.getString(R.string.color_picker_preview_empty)
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        previewRow.addView(swatch)
        previewRow.addView(previewLabel)
        colorPreviewSwatch = swatch
        colorPreviewLabel = previewLabel

        val checkButton = Button(service).apply {
            text = service.getString(R.string.color_picker_check)
            textSize = 14f
            setOnClickListener { onCheck() }
        }
        val buttonRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancelButton = Button(service).apply {
            text = "キャンセル"
            textSize = 14f
            setOnClickListener { onCancel() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }
        val doneButton = Button(service).apply {
            text = "決定"
            textSize = 14f
            setOnClickListener { onDone() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }
        buttonRow.addView(cancelButton)
        buttonRow.addView(doneButton)
        bar.addView(dragHandle)
        bar.addView(titleView)
        bar.addView(previewRow)
        bar.addView(checkButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        bar.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        val params = overlayParams(
            width = maxWidth.coerceAtMost(dp(360)),
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        makeControlBarDraggable(dragHandle, bar, params)
        windowManager.addView(bar, params)
        controlBar = bar
    }

    private fun sampleColorAtMarker(onFinished: (Result<Int>) -> Unit) {
        val marker = markerWindows.firstOrNull() ?: return
        val request = colorSampleRequest ?: return
        val x = marker.centerX.roundToInt()
        val y = marker.centerY.roundToInt()
        colorPreviewLabel?.text = "取得中…"
        // スクショに照準が写らないよう一時非表示
        marker.view.visibility = View.INVISIBLE
        controlBar?.visibility = View.INVISIBLE
        marker.view.postDelayed({
            request(x, y) { result ->
                marker.view.visibility = View.VISIBLE
                controlBar?.visibility = View.VISIBLE
                result.onSuccess { color ->
                    lastColorSample = color
                    lastColorSampleX = x
                    lastColorSampleY = y
                    colorPreviewSwatch?.background =
                        roundedBackground(color or 0xFF000000.toInt(), dp(8).toFloat())
                    colorPreviewLabel?.text =
                        String.format("#%06X  (%d, %d)", color and 0xFFFFFF, x, y)
                }.onFailure { error ->
                    lastColorSample = null
                    colorPreviewLabel?.text = "取得失敗: ${error.message}"
                }
                onFinished(result)
            }
        }, 80L)
    }

    private fun addMarker(
        label: String,
        color: Int,
        centerX: Float,
        centerY: Float,
        onMoved: (() -> Unit)? = null,
    ): MarkerWindow {
        val size = dp(54)
        val screen = service.overlayScreenBounds()
        val marker = TextView(service).apply {
            text = label
            textSize = if (label.length > 2) 13f else if (label.length > 1) 15f else 19f
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            placeMarkerCenter(this, size, centerX, centerY, screen)
        }
        makeMarkerDraggable(marker, params, size, onMoved)
        windowManager.addView(marker, params)
        return MarkerWindow(marker, params, size)
    }

    private fun showControlBar(
        title: String,
        onDone: () -> Unit,
        onCancel: () -> Unit,
    ) {
        val screen = service.overlayScreenBounds()
        val maxWidth = screen.width - dp(16)
        val bar = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(0xFF1E1C24.toInt(), dp(16).toFloat())
            minimumWidth = dp(220)
        }
        val dragHandle = TextView(service).apply {
            text = "↕"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
            contentDescription = "位置指定パネルを移動"
        }
        val titleView = TextView(service).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        val buttonRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancelButton = Button(service).apply {
            text = "キャンセル"
            textSize = 14f
            setOnClickListener { onCancel() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }
        val doneButton = Button(service).apply {
            text = "決定"
            textSize = 14f
            setOnClickListener { onDone() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }
        buttonRow.addView(cancelButton)
        buttonRow.addView(doneButton)
        bar.addView(dragHandle)
        bar.addView(titleView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        bar.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val params = overlayParams(
            width = maxWidth.coerceAtMost(dp(360)),
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        windowManager.addView(view, params)
        lineView = view
    }

    private fun showBulkSwipeLines() {
        val view = BulkSwipeLinesView(service)
        val params = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        windowManager.addView(view, params)
        bulkLineView = view
    }

    private fun makeMarkerDraggable(
        marker: View,
        params: WindowManager.LayoutParams,
        size: Int,
        onMoved: (() -> Unit)? = null,
    ) {
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
                    val screen = service.overlayScreenBounds()
                    params.x = (event.rawX - offsetX).roundToInt()
                    params.y = (event.rawY - offsetY).roundToInt()
                    clampMarkerParams(params, size, screen)
                    windowManager.updateViewLayout(marker, params)
                    onMoved?.invoke()
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
                    val screen = service.overlayScreenBounds()
                    params.x = (downX + (event.rawX - downRawX).roundToInt())
                        .coerceIn(0, (screen.width - bar.width).coerceAtLeast(0))
                    params.y = (downY + (event.rawY - downRawY).roundToInt())
                        .coerceIn(0, (screen.height - bar.height).coerceAtLeast(0))
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

    private fun buildUpdatedConfig(): AutomationConfig? {
        val original = bulkConfig ?: return null
        var updated = original
        bulkMarkers.groupBy { it.actionId }.forEach { (actionId, markers) ->
            val current = AutomationConfigEditor.findAction(updated, actionId) ?: return@forEach
            val replaced = when (current) {
                is AutomationAction.Tap -> {
                    val marker = markers.single { it.role == BulkMarkerRole.TAP }
                    current.copy(x = marker.window.centerX, y = marker.window.centerY)
                }
                is AutomationAction.Swipe -> {
                    val start = markers.first { it.role == BulkMarkerRole.SWIPE_START }
                    val end = markers.first { it.role == BulkMarkerRole.SWIPE_END }
                    current.copy(
                        startX = start.window.centerX,
                        startY = start.window.centerY,
                        endX = end.window.centerX,
                        endY = end.window.centerY,
                    )
                }
                else -> return@forEach
            }
            updated = AutomationConfigEditor.replace(updated, replaced)
        }
        return updated
    }

    private fun showRegionRect() {
        val view = RegionRectView(service)
        val params = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        windowManager.addView(view, params)
        regionView = view
    }

    private fun updateRegion() {
        if (markerWindows.size < 2) return
        regionView?.setRect(
            markerWindows[0].centerX,
            markerWindows[0].centerY,
            markerWindows[1].centerX,
            markerWindows[1].centerY,
        )
    }

    private fun updateLine() {
        if (markerWindows.size != 2 || bulkConfig != null) return
        lineView?.setPoints(
            markerWindows[0].centerX,
            markerWindows[0].centerY,
            markerWindows[1].centerX,
            markerWindows[1].centerY,
        )
    }

    private fun updateBulkLines() {
        val segments = bulkMarkers
            .groupBy { it.actionId }
            .mapNotNull { (_, markers) ->
                val start = markers.firstOrNull { it.role == BulkMarkerRole.SWIPE_START } ?: return@mapNotNull null
                val end = markers.firstOrNull { it.role == BulkMarkerRole.SWIPE_END } ?: return@mapNotNull null
                SwipeSegment(
                    start.window.centerX,
                    start.window.centerY,
                    end.window.centerX,
                    end.window.centerY,
                )
            }
        bulkLineView?.setSegments(segments)
    }

    private fun overlayParams(width: Int, height: Int, flags: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).also { params ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    /** マーカー中心を画面端（ナビバー込み）まで移動できるよう、ビューは画面外にはみ出してよい */
    private fun placeMarkerCenter(
        params: WindowManager.LayoutParams,
        size: Int,
        centerX: Float,
        centerY: Float,
        screen: ScreenBounds,
    ) {
        val maxX = (screen.width - 1).coerceAtLeast(0).toFloat()
        val maxY = (screen.height - 1).coerceAtLeast(0).toFloat()
        params.x = (centerX.coerceIn(0f, maxX) - size / 2f).roundToInt()
        params.y = (centerY.coerceIn(0f, maxY) - size / 2f).roundToInt()
    }

    private fun clampMarkerParams(
        params: WindowManager.LayoutParams,
        size: Int,
        screen: ScreenBounds,
    ) {
        val maxX = (screen.width - 1).coerceAtLeast(0)
        val maxY = (screen.height - 1).coerceAtLeast(0)
        val half = size / 2
        params.x = params.x.coerceIn(-half, maxX - half)
        params.y = params.y.coerceIn(-half, maxY - half)
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).roundToInt()

    private enum class BulkMarkerRole {
        TAP,
        SWIPE_START,
        SWIPE_END,
    }

    private data class BulkMarker(
        val actionId: String,
        val role: BulkMarkerRole,
        val window: MarkerWindow,
    )

    private data class MarkerWindow(
        val view: View,
        val params: WindowManager.LayoutParams,
        val size: Int,
    ) {
        val centerX: Float get() = params.x + size / 2f
        val centerY: Float get() = params.y + size / 2f
    }

    private data class SwipeSegment(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
    )

    private class ColorCrosshairView(service: AccessibilityService) : View(service) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(236, 64, 122)
            style = Paint.Style.STROKE
            strokeWidth = service.resources.displayMetrics.density * 3f
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = service.resources.displayMetrics.density * 2f
        }
        private val density = service.resources.displayMetrics.density

        init {
            contentDescription = "色を取る位置"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val outer = minOf(width, height) / 2f - density * 2f
            val inner = density * 8f
            // 中央は塗りつぶさないので、下の実画面の色が見える
            canvas.drawCircle(cx, cy, outer, ring)
            canvas.drawCircle(cx, cy, inner, ring)
            val gap = density * 5f
            canvas.drawLine(cx, density * 4f, cx, cy - gap, cross)
            canvas.drawLine(cx, cy + gap, cx, height - density * 4f, cross)
            canvas.drawLine(density * 4f, cy, cx - gap, cy, cross)
            canvas.drawLine(cx + gap, cy, width - density * 4f, cy, cross)
        }
    }

    private class RegionRectView(service: AccessibilityService) : View(service) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 106, 76, 147)
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 206, 147, 216)
            style = Paint.Style.STROKE
            strokeWidth = service.resources.displayMetrics.density * 3f
        }
        private var left = 0f
        private var top = 0f
        private var right = 0f
        private var bottom = 0f

        fun setRect(left: Float, top: Float, right: Float, bottom: Float) {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rectLeft = minOf(left, right)
            val rectTop = minOf(top, bottom)
            val rectRight = maxOf(left, right)
            val rectBottom = maxOf(top, bottom)
            canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, fill)
            canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, stroke)
        }
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
            drawArrowLine(canvas, startX, startY, endX, endY, paint)
        }
    }

    private class BulkSwipeLinesView(service: AccessibilityService) : View(service) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 193, 7)
            strokeWidth = service.resources.displayMetrics.density * 3f
            style = Paint.Style.STROKE
        }
        private var segments = emptyList<SwipeSegment>()

        fun setSegments(segments: List<SwipeSegment>) {
            this.segments = segments
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            segments.forEach { segment ->
                drawArrowLine(
                    canvas,
                    segment.startX,
                    segment.startY,
                    segment.endX,
                    segment.endY,
                    paint,
                )
            }
        }
    }

    private companion object {
        fun drawArrowLine(
            canvas: Canvas,
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            paint: Paint,
        ) {
            canvas.drawLine(startX, startY, endX, endY, paint)
            val angle = atan2(endY - startY, endX - startX)
            val arrow = paint.strokeWidth * 3.5f
            canvas.drawLine(
                endX,
                endY,
                endX - arrow * cos(angle - 0.55f),
                endY - arrow * sin(angle - 0.55f),
                paint,
            )
            canvas.drawLine(
                endX,
                endY,
                endX - arrow * cos(angle + 0.55f),
                endY - arrow * sin(angle + 0.55f),
                paint,
            )
        }
    }
}
