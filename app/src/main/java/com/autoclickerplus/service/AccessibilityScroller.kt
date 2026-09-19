package com.autoclickerplus.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.autoclickerplus.engine.GesturePoint
import com.autoclickerplus.engine.ScrollWay
import com.autoclickerplus.engine.scrollWayFromSwipe
import kotlinx.coroutines.delay

class AccessibilityScroller(
    private val service: AccessibilityService,
) {
    suspend fun scrollToEnd(start: GesturePoint, end: GesturePoint): Boolean {
        val way = scrollWayFromSwipe(start, end)
        val x = ((start.x + end.x) / 2f).toInt()
        val y = ((start.y + end.y) / 2f).toInt()
        val roots = windowRoots()
        try {
            for (root in roots) {
                val target = findScrollableNode(root, x, y, way) ?: continue
                if (scrollNodeToEnd(target, way)) return true
            }
        } finally {
            roots.forEach { recycleQuietly(it) }
        }
        return false
    }

    private suspend fun scrollNodeToEnd(
        node: AccessibilityNodeInfo,
        way: ScrollWay,
    ): Boolean {
        val jumped = jumpToCollectionEnd(node, way)
        val granular = granularScrollToEnd(node, way)
        var moved = jumped || granular
        repeat(MAX_SCROLL_STEPS) {
            if (!node.refresh()) return moved
            val action = pickAction(node, way) ?: return true
            if (!node.performAction(action)) return moved
            moved = true
            delay(SCROLL_STEP_DELAY_MS)
        }
        return moved
    }

    private fun jumpToCollectionEnd(node: AccessibilityNodeInfo, way: ScrollWay): Boolean {
        if (!hasAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id)) {
            return false
        }
        val info = node.collectionInfo ?: return false
        val lastRow = (info.rowCount - 1).coerceAtLeast(0)
        val lastCol = (info.columnCount - 1).coerceAtLeast(0)
        if (info.rowCount <= 0 && info.columnCount <= 0) return false
        val args = Bundle()
        when (way) {
            ScrollWay.DOWN, ScrollWay.RIGHT -> {
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, lastRow)
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, lastCol)
            }
            ScrollWay.UP, ScrollWay.LEFT -> {
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 0)
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0)
            }
        }
        return node.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
            args,
        )
    }

    private fun granularScrollToEnd(node: AccessibilityNodeInfo, way: ScrollWay): Boolean {
        val action = pickAction(node, way) ?: return false
        val args = Bundle().apply {
            putFloat(PLATFORM_SCROLL_AMOUNT, Float.POSITIVE_INFINITY)
            putFloat(ANDROIDX_SCROLL_AMOUNT, Float.POSITIVE_INFINITY)
        }
        return node.performAction(action, args)
    }

    private fun findScrollableNode(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        way: ScrollWay,
    ): AccessibilityNodeInfo? {
        val atPoint = findDeepestNodeAt(root, x, y)
        var current = atPoint
        while (current != null) {
            if (isUsable(current) && canScroll(current, way)) return current
            current = current.parent
        }
        return findFirstScrollable(root, way)
    }

    private fun findDeepestNodeAt(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
    ): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null
        var best = node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val hit = findDeepestNodeAt(child, x, y)
            if (hit != null) best = hit
        }
        return best
    }

    private fun findFirstScrollable(
        node: AccessibilityNodeInfo,
        way: ScrollWay,
    ): AccessibilityNodeInfo? {
        if (isUsable(node) && canScroll(node, way)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findFirstScrollable(child, way)?.let { return it }
        }
        return null
    }

    private fun canScroll(node: AccessibilityNodeInfo, way: ScrollWay): Boolean {
        if (pickAction(node, way) != null) return true
        return node.isScrollable
    }

    private fun pickAction(node: AccessibilityNodeInfo, way: ScrollWay): Int? {
        val available = node.actionList.map { it.id }.toSet()
        return actionIds(way).firstOrNull { it in available }
    }

    private fun hasAction(node: AccessibilityNodeInfo, action: Int): Boolean =
        node.actionList.any { it.id == action }

    private fun actionIds(way: ScrollWay): List<Int> {
        val directional = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (way) {
                ScrollWay.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                ScrollWay.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                ScrollWay.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                ScrollWay.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            }
        } else {
            null
        }
        val logical = when (way) {
            ScrollWay.DOWN, ScrollWay.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollWay.UP, ScrollWay.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return listOfNotNull(directional, logical)
    }

    private fun isUsable(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        return node.packageName?.toString() != service.packageName
    }

    private fun windowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        service.windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNullTo(roots) { it.root }
        if (roots.isEmpty()) {
            service.rootInActiveWindow?.let { roots += it }
        }
        return roots
    }

    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        @Suppress("DEPRECATION")
        try {
            node.recycle()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val MAX_SCROLL_STEPS = 80
        const val SCROLL_STEP_DELAY_MS = 32L
        const val PLATFORM_SCROLL_AMOUNT =
            "android.view.accessibility.action.ARGUMENT_SCROLL_AMOUNT_FLOAT"
        const val ANDROIDX_SCROLL_AMOUNT =
            "androidx.core.view.accessibility.action.ARGUMENT_SCROLL_AMOUNT_FLOAT"
    }
}
