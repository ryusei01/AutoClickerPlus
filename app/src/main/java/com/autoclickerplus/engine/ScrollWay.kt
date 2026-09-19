package com.autoclickerplus.engine

import kotlin.math.abs

enum class ScrollWay {
    DOWN,
    UP,
    LEFT,
    RIGHT,
}

fun scrollWayFromSwipe(start: GesturePoint, end: GesturePoint): ScrollWay {
    val dx = end.x - start.x
    val dy = end.y - start.y
    return if (abs(dy) >= abs(dx)) {
        if (dy <= 0f) ScrollWay.DOWN else ScrollWay.UP
    } else {
        if (dx <= 0f) ScrollWay.LEFT else ScrollWay.RIGHT
    }
}
