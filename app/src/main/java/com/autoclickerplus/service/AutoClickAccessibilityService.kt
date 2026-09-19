package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.autoclickerplus.data.AutomationRepository
import com.autoclickerplus.engine.AutomationRunner
import com.autoclickerplus.engine.ConditionEvaluator
import com.autoclickerplus.engine.GestureExecutor
import com.autoclickerplus.engine.GesturePoint
import com.autoclickerplus.engine.RunnerState
import com.autoclickerplus.engine.ScreenBounds
import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.AutomationConfigEditor
import com.autoclickerplus.model.BranchSide
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AutoClickAccessibilityService : AccessibilityService(), GestureExecutor {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: AutomationRepository
    private lateinit var runner: AutomationRunner
    private lateinit var conditionEvaluator: AccessibilityConditionEvaluator
    private lateinit var overlay: OverlayController
    private val configMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentConfig = AutomationConfig()

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = AutomationRepository(applicationContext)
        conditionEvaluator = AccessibilityConditionEvaluator(this)
        runner = AutomationRunner(
            scope = serviceScope,
            executor = this,
            conditionEvaluator = ConditionEvaluator { conditions, operator ->
                overlay.hideTransientOverlays()
                conditionEvaluator.evaluate(conditions, operator)
            },
        )
        overlay = OverlayController(
            service = this,
            callbacks = OverlayCallbacks(
                onStart = ::startConfiguredAutomation,
                onStop = { runner.stop() },
                onAddTap = { addAndPick(AutomationAction.Tap()) },
                onAddSwipe = { addAndPick(AutomationAction.Swipe()) },
                onAddIf = {
                    mutateConfig {
                        AutomationConfigEditor.add(it, AutomationAction.IfBlock())
                    }
                    overlay.showEditor()
                },
                onAddToBranch = { blockId, side, action ->
                    addToBranchAndMaybePick(blockId, side, action)
                },
                onReplace = { action ->
                    mutateConfig { AutomationConfigEditor.replace(it, action) }
                },
                onAddCondition = { blockId, condition ->
                    mutateConfig {
                        AutomationConfigEditor.addCondition(it, blockId, condition)
                    }
                },
                onReplaceCondition = { blockId, condition ->
                    mutateConfig {
                        AutomationConfigEditor.replaceCondition(it, blockId, condition)
                    }
                },
                onRemoveCondition = { blockId, conditionId ->
                    mutateConfig {
                        AutomationConfigEditor.removeCondition(it, blockId, conditionId)
                    }
                },
                onPickColor = ::pickColorCondition,
                onRemove = { actionId ->
                    mutateConfig { AutomationConfigEditor.remove(it, actionId) }
                },
                onMove = { actionId, offset ->
                    mutateConfig { AutomationConfigEditor.move(it, actionId, offset) }
                },
                onRepeatMode = { mode ->
                    mutateConfig { AutomationConfigEditor.setRepeatMode(it, mode) }
                },
                onRepeatCount = { count ->
                    mutateConfig { AutomationConfigEditor.setRepeatCount(it, count) }
                },
            ),
        )
        activeService = this
        overlay.showControls()
        serviceScope.launch {
            runner.state.collect(overlay::updateRunnerState)
        }
        serviceScope.launch {
            repository.migrateLegacyIfNeeded()
            repository.config.collect { config ->
                if (currentConfig != config) runner.stop()
                currentConfig = config
                overlay.updateConfig(config)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        if (::runner.isInitialized) runner.stop()
    }

    override fun onDestroy() {
        if (::runner.isInitialized) runner.stop()
        if (::overlay.isInitialized) overlay.removeAll()
        if (activeService === this) activeService = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override suspend fun tap(point: GesturePoint): Boolean {
        val path = Path().apply { moveTo(point.x, point.y) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
                .build(),
        )
    }

    override suspend fun swipe(
        start: GesturePoint,
        end: GesturePoint,
        durationMs: Long,
        stopAtEnd: Boolean,
    ): Boolean {
        val movePath = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        if (!stopAtEnd) {
            return dispatch(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(movePath, 0L, durationMs))
                    .build(),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val moveStroke = GestureDescription.StrokeDescription(
                movePath,
                0L,
                durationMs,
                true,
            )
            val holdPath = Path().apply { moveTo(end.x, end.y) }
            val holdStroke = moveStroke.continueStroke(
                holdPath,
                durationMs,
                HOLD_AT_END_MS,
                false,
            )
            return dispatch(
                GestureDescription.Builder()
                    .addStroke(moveStroke)
                    .addStroke(holdStroke)
                    .build(),
            )
        }
        val moved = dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(movePath, 0L, durationMs))
                .build(),
        )
        if (!moved) return false
        val holdPath = Path().apply { moveTo(end.x, end.y) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(holdPath, 0L, HOLD_AT_END_MS))
                .build(),
        )
    }

    private fun startConfiguredAutomation() {
        serviceScope.launch {
            val config = repository.config.first()
            currentConfig = config
            overlay.updateConfig(config)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                config.actions.any { it.containsColorCondition() }
            ) {
                Toast.makeText(
                    this@AutoClickAccessibilityService,
                    "色条件にはAndroid 11以降が必要です",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            runner.start(config, screenBounds())
        }
    }

    private fun pickCoordinates(actionId: String) {
        if (!::overlay.isInitialized) return
        serviceScope.launch {
            runner.stop()
            currentConfig = repository.config.first()
            overlay.updateConfig(currentConfig)
            overlay.showPicker(actionId)
        }
    }

    private fun addAndPick(action: AutomationAction) {
        serviceScope.launch {
            updateConfig { AutomationConfigEditor.add(it, action) }
            overlay.showPicker(action.id, removeOnCancel = true)
        }
    }

    private fun addToBranchAndMaybePick(
        blockId: String,
        side: BranchSide,
        action: AutomationAction,
    ) {
        serviceScope.launch {
            updateConfig { AutomationConfigEditor.addToBranch(it, blockId, side, action) }
            overlay.showEditor()
            if (action is AutomationAction.Tap || action is AutomationAction.Swipe) {
                overlay.showPicker(action.id, removeOnCancel = true)
            }
        }
    }

    private fun pickColorCondition(ifBlockId: String, conditionId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "色判定にはAndroid 11以降が必要です", Toast.LENGTH_LONG).show()
            return
        }
        serviceScope.launch {
            runner.stop()
            currentConfig = repository.config.first()
            overlay.updateConfig(currentConfig)
            val block = AutomationConfigEditor.findAction(currentConfig, ifBlockId)
                as? AutomationAction.IfBlock ?: return@launch
            val condition = block.conditions.firstOrNull { it.id == conditionId }
                as? AutomationCondition.PixelColor ?: return@launch
            overlay.showColorPicker(condition.x, condition.y) { x, y ->
                serviceScope.launch {
                    runCatching { conditionEvaluator.sampleColor(x, y) }
                        .onSuccess { color ->
                            updateConfig {
                                AutomationConfigEditor.replaceCondition(
                                    it,
                                    ifBlockId,
                                    condition.copy(x = x, y = y, argb = color),
                                )
                            }
                        }
                        .onFailure {
                            Toast.makeText(
                                this@AutoClickAccessibilityService,
                                "色を取得できません: ${it.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    overlay.showEditor()
                }
            }
        }
    }

    private fun mutateConfig(transform: (AutomationConfig) -> AutomationConfig) {
        serviceScope.launch { updateConfig(transform) }
    }

    private suspend fun updateConfig(
        transform: (AutomationConfig) -> AutomationConfig,
    ): AutomationConfig = configMutex.withLock {
        runner.stop()
        val latest = repository.config.first()
        val updated = transform(latest)
        currentConfig = updated
        overlay.updateConfig(updated)
        repository.save(updated)
        updated
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val started = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        mainHandler.post { continuation.resumeIfActive(true) }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        mainHandler.post { continuation.resumeIfActive(false) }
                    }
                },
                null,
            )
            if (!started) mainHandler.post { continuation.resumeIfActive(false) }
        }

    @Suppress("DEPRECATION")
    private fun screenBounds(): ScreenBounds {
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            ScreenBounds(bounds.width(), bounds.height())
        } else {
            val metrics = resources.displayMetrics
            ScreenBounds(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun CancellableContinuation<Boolean>.resumeIfActive(value: Boolean) {
        if (isActive) resume(value)
    }

    private fun AutomationAction.containsColorCondition(): Boolean = when (this) {
        is AutomationAction.Tap, is AutomationAction.Swipe -> false
        is AutomationAction.IfBlock ->
            conditions.any { it is com.autoclickerplus.model.AutomationCondition.PixelColor } ||
                thenActions.any { it.containsColorCondition() } ||
                elseActions.any { it.containsColorCondition() }
    }

    companion object {
        private const val HOLD_AT_END_MS = 180L

        @Volatile
        private var activeService: AutoClickAccessibilityService? = null

        val isConnected: Boolean get() = activeService != null
        val runnerState: StateFlow<RunnerState>? get() = activeService?.runner?.state

        fun showControls(): Boolean {
            val service = activeService ?: return false
            service.overlay.showControls()
            return true
        }

        fun requestCoordinatePick(actionId: String): Boolean {
            val service = activeService ?: return false
            service.pickCoordinates(actionId)
            return true
        }

        fun requestColorPick(ifBlockId: String, conditionId: String): Boolean {
            val service = activeService ?: return false
            service.pickColorCondition(ifBlockId, conditionId)
            return true
        }
    }
}
