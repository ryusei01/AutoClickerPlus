package com.autoclickerplus.service

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import com.autoclickerplus.engine.ScreenBounds

/** ナビバー込みの実画面サイズ（位置指定・ジェスチャー共通） */
fun Context.overlayScreenBounds(): ScreenBounds {
    val windowManager = getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.maximumWindowMetrics.bounds
        ScreenBounds(bounds.width(), bounds.height())
    } else {
        @Suppress("DEPRECATION")
        val size = Point()
        windowManager.defaultDisplay.getRealSize(size)
        ScreenBounds(size.x, size.y)
    }
}
