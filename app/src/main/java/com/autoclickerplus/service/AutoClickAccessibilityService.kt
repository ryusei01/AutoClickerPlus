package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.autoclickerplus.data.AutomationRepository
import com.autoclickerplus.engine.AutomationRunner
import com.autoclickerplus.engine.GestureExecutor
import com.autoclickerplus.engine.GesturePoint
import com.autoclickerplus.engine.RunnerState
import com.autoclickerplus.engine.ScreenBounds
import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.AutomationConfigEditor
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
    private lateinit var overlay: OverlayController
    private val configMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentConfig = AutomationConfig()

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = AutomationRepository(applicationContext)
        runner = AutomationRunner(serviceScope, this)
        overlay = OverlayController(
            service = this,
            callbacks = OverlayCallbacks(
                onStart = ::startConfiguredAutomation,
                onStop = { runner.stop() },
                onAddTap = { addAndPick(AutomationAction.Tap()) },
                onAddSwipe = { addAndPick(AutomationAction.Swipe()) },
                onReplace = { action ->
                    mutateConfig { AutomationConfigEditor.replace(it, action) }
                },
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
            repository.config.collect { config ->
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
    ): Boolean {
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(),
        )
    }

    private fun startConfiguredAutomation() {
        serviceScope.launch {
            val config = repository.config.first()
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

    companion object {
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
    }
}
