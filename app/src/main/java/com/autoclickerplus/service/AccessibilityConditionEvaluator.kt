package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.graphics.get
import com.autoclickerplus.engine.ConditionEvaluationException
import com.autoclickerplus.engine.ConditionEvaluator
import com.autoclickerplus.engine.ConditionLogic
import com.autoclickerplus.engine.UiNodeSnapshot
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
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) {
                throw ConditionEvaluationException("色取得の座標が画面外です")
            }
            bitmap[x, y]
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
                    if (condition.x !in 0 until bitmap.width ||
                        condition.y !in 0 until bitmap.height
                    ) {
                        throw ConditionEvaluationException("色判定の座標が画面外です")
                    }
                    ConditionLogic.colorsMatch(
                        bitmap[condition.x, condition.y],
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

    private fun captureNodes(): List<UiNodeSnapshot> {
        val root = service.rootInActiveWindow
            ?: throw ConditionEvaluationException("画面の文字情報を取得できません")
        val metrics = service.resources.displayMetrics
        val screenWidth = metrics.widthPixels.coerceAtLeast(1)
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)
        return try {
            buildList { collectNodes(root, this, screenWidth, screenHeight) }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        destination: MutableList<UiNodeSnapshot>,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        if (node.isVisibleToUser && node.packageName != service.packageName) {
            val bounds = Rect().also(node::getBoundsInScreen)
            val normalizedCenterX = if (bounds.width() > 0) {
                (bounds.exactCenterX() / screenWidth.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
            val normalizedCenterY = if (bounds.height() > 0) {
                (bounds.exactCenterY() / screenHeight.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
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
                enabled = node.isEnabled,
                clickable = node.isClickable,
                normalizedCenterX = normalizedCenterX,
                normalizedCenterY = normalizedCenterY,
            )
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectNodes(child, destination, screenWidth, screenHeight)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
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
