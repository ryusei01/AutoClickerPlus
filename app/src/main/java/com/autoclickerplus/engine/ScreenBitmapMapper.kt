package com.autoclickerplus.engine

/** 画面座標をスクリーンショットの画素座標へ写す（解像度差・機種差の補正） */
fun mapScreenPointToBitmap(
    x: Int,
    y: Int,
    screenWidth: Int,
    screenHeight: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
): Pair<Int, Int> {
    if (bitmapWidth <= 0 || bitmapHeight <= 0) return 0 to 0
    if (screenWidth <= 0 || screenHeight <= 0) {
        return x.coerceIn(0, bitmapWidth - 1) to y.coerceIn(0, bitmapHeight - 1)
    }
    if (bitmapWidth == screenWidth && bitmapHeight == screenHeight) {
        return x.coerceIn(0, bitmapWidth - 1) to y.coerceIn(0, bitmapHeight - 1)
    }
    val mappedX = (x.toLong() * bitmapWidth / screenWidth).toInt()
    val mappedY = (y.toLong() * bitmapHeight / screenHeight).toInt()
    return mappedX.coerceIn(0, bitmapWidth - 1) to mappedY.coerceIn(0, bitmapHeight - 1)
}
