package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.graphics.get
import com.autoclickerplus.engine.ConditionEvaluationException
import com.autoclickerplus.engine.ConditionEvaluator
import com.autoclickerplus.engine.ConditionLogic
import com.autoclickerplus.engine.UiNodeSnapshot
import com.autoclickerplus.engine.mapScreenPointToBitmap
import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.ConditionOperator
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class AccessibilityConditionEvaluator(
    private val service: AccessibilityService,
) : ConditionEvaluator {
    private val screenshotMutex = Mutex()
    private var lastScreenshotAt = 0L

    suspend fun sampleColor(x: Int, y: Int): Int {
        val bitmap = captureScreenshot()
        return try {
            val (bx, by) = mapToBitmap(x, y, bitmap)
            bitmap[bx, by]
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun evaluate(
        conditions: List<AutomationCondition>,
        operator: ConditionOperator,
    ): Boolean {
        if (conditions.isEmpty()) return true
        var nodeSnapshots: List<UiNodeSnapshot>? = null
        var screenshot: Bitmap? = null
        try {
            suspend fun evaluateOne(condition: AutomationCondition): Boolean = when (condition) {
                is AutomationCondition.TextExists -> {
                    val nodes = nodeSnapshots ?: captureNodes().also { nodeSnapshots = it }
                    ConditionLogic.textExists(nodes, condition)
                }
                is AutomationCondition.UiState -> {
                    val nodes = nodeSnapshots ?: captureNodes().also { nodeSnapshots = it }
                    ConditionLogic.uiStateMatches(nodes, condition)
                }
                is AutomationCondition.PixelColor -> {
                    val bitmap = screenshot ?: captureScreenshot().also { screenshot = it }
                    val (bx, by) = mapToBitmap(condition.x, condition.y, bitmap)
                    ConditionLogic.colorsMatch(
                        bitmap[bx, by],
                        condition.argb,
                        condition.tolerance,
                    )
                }
            }

            return when (operator) {
                ConditionOperator.AND -> {
                    for (condition in conditions) {
                        if (!evaluateOne(condition)) return false
                    }
                    true
                }
                ConditionOperator.OR -> {
                    for (condition in conditions) {
                        if (evaluateOne(condition)) return true
                    }
                    false
                }
            }
        } finally {
            screenshot?.recycle()
        }
    }

    private fun mapToBitmap(x: Int, y: Int, bitmap: Bitmap): Pair<Int, Int> {
        val screen = service.overlayScreenBounds()
        val mapped = mapScreenPointToBitmap(
            x = x,
            y = y,
            screenWidth = screen.width,
            screenHeight = screen.height,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
        )
        if (mapped.first !in 0 until bitmap.width || mapped.second !in 0 until bitmap.height) {
            throw ConditionEvaluationException("色判定の座標が画面外です")
        }
        return mapped
    }

    private fun captureNodes(): List<UiNodeSnapshot> {
        val roots = collectWindowRoots()
        if (roots.isEmpty()) {
            throw ConditionEvaluationException("画面の文字情報を取得できません")
        }
        return try {
            buildList {
                roots.forEach { root ->
                    root.refresh()
                    collectNodes(root, this, parentIndex = -1)
                }
            }
        } finally {
            roots.forEach { root ->
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
    }

    private fun collectWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            service.windows
                ?.asSequence()
                ?.filter { window ->
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
                ?.mapNotNull { it.root }
                ?.forEach { roots += it }
        }
        if (roots.isEmpty()) {
            service.rootInActiveWindow?.let { roots += it }
        }
        return roots
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        destination: MutableList<UiNodeSnapshot>,
        parentIndex: Int,
    ) {
        val currentIndex = if (node.packageName == service.packageName) {
            parentIndex
        } else {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val index = destination.size
            destination += UiNodeSnapshot(
                values = listOfNotNull(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        node.hintText?.toString()
                    } else {
                        null
                    },
                ),
                enabled = !nodeLooksDisabled(node),
                clickable = node.isClickable ||
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                visible = node.isVisibleToUser,
                parentIndex = parentIndex,
            )
            index
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectNodes(child, destination, currentIndex)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    private fun nodeLooksDisabled(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ConditionLogic.indicatesDisabledState(node.stateDescription?.toString())
        ) {
            return true
        }
        val viewId = node.viewIdResourceName
        if (viewId != null &&
            (viewId.contains("disabled", ignoreCase = true) ||
                viewId.contains("inactive", ignoreCase = true) ||
                viewId.contains("非活性"))
        ) {
            return true
        }
        return false
    }

    private suspend fun captureScreenshot(): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw ConditionEvaluationException("色判定にはAndroid 11以降が必要です")
        }
        return screenshotMutex.withLock {
            val elapsed = SystemClock.elapsedRealtime() - lastScreenshotAt
            if (elapsed < MIN_SCREENSHOT_INTERVAL_MS) {
                delay(MIN_SCREENSHOT_INTERVAL_MS - elapsed)
            }
            captureScreenshotApi30().also {
                lastScreenshotAt = SystemClock.elapsedRealtime()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenshotApi30(): Bitmap =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(
                        screenshot: AccessibilityService.ScreenshotResult,
                    ) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(
                                hardwareBuffer,
                                screenshot.colorSpace,
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            hardwareBuffer.close()
                        }
                        if (bitmap == null) {
                            if (continuation.isActive) {
                                continuation.resumeWith(
                                    Result.failure(
                                        ConditionEvaluationException(
                                            "スクリーンショットを画像へ変換できません",
                                        ),
                                    ),
                                )
                            }
                        } else if (continuation.isActive) {
                            continuation.resume(bitmap)
                        } else {
                            bitmap.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.failure(
                                    ConditionEvaluationException(
                                        "スクリーンショット取得に失敗しました ($errorCode)",
                                    ),
                                ),
                            )
                        }
                    }
                },
            )
        }

    private companion object {
        const val MIN_SCREENSHOT_INTERVAL_MS = 500L
    }
}
