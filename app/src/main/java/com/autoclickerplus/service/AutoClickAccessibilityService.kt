package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.autoclickerplus.data.AutomationRepository
import com.autoclickerplus.engine.AutomationRunner
import com.autoclickerplus.engine.GestureExecutor
import com.autoclickerplus.engine.GesturePoint
import com.autoclickerplus.engine.RunnerState
import com.autoclickerplus.engine.ScreenBounds
import com.autoclickerplus.model.AutomationAction
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AutoClickAccessibilityService : AccessibilityService(), GestureExecutor {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: AutomationRepository
    private lateinit var runner: AutomationRunner
    private lateinit var overlay: OverlayController

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = AutomationRepository(applicationContext)
        runner = AutomationRunner(serviceScope, this)
        overlay = OverlayController(
            service = this,
            onStart = ::startConfiguredAutomation,
            onStop = { runner.stop() },
        )
        activeService = this
        overlay.showControls()
        serviceScope.launch {
            runner.state.collect(overlay::updateRunnerState)
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
            val config = repository.config.first()
            val action = config.actions.firstOrNull { it.id == actionId } ?: return@launch
            runner.stop()
            overlay.showPicker(action) { updatedAction ->
                serviceScope.launch {
                    val latest = repository.config.first()
                    repository.save(
                        latest.copy(
                            actions = latest.actions.map {
                                if (it.id == updatedAction.id) updatedAction else it
                            },
                        ),
                    )
                }
            }
        }
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val started = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        continuation.resumeIfActive(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        continuation.resumeIfActive(false)
                    }
                },
                null,
            )
            if (!started) continuation.resumeIfActive(false)
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
