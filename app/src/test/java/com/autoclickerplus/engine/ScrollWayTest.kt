package com.autoclickerplus.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollWayTest {
    @Test
    fun fingerUpMeansScrollDown() {
        assertEquals(
            ScrollWay.DOWN,
            scrollWayFromSwipe(GesturePoint(540f, 1500f), GesturePoint(540f, 700f)),
        )
    }

    @Test
    fun fingerDownMeansScrollUp() {
        assertEquals(
            ScrollWay.UP,
            scrollWayFromSwipe(GesturePoint(540f, 700f), GesturePoint(540f, 1500f)),
        )
    }

    @Test
    fun fingerLeftMeansScrollLeft() {
        assertEquals(
            ScrollWay.LEFT,
            scrollWayFromSwipe(GesturePoint(800f, 1000f), GesturePoint(200f, 1000f)),
        )
    }
}
