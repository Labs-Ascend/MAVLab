package com.ascend.mavlab.core.ui.navigation

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Guarded gestures drive a real pager: no rubber band between pages or release-time teleport. */
@Stable
class ScreenSwipeMotion internal constructor(
    val pager: PagerState,
    private val scope: CoroutineScope,
    initialPage: Int,
) {
    var selectedPage by mutableIntStateOf(initialPage)
        private set
    private var animation: Job? = null
    private var dragging = false
    private var dragStart = 0f
    private var releaseVelocity = 0f

    val position: Float get() = (pager.currentPage + pager.currentPageOffsetFraction) * pager.layoutInfo.pageSize
    val offset: Float get() = selectedPage * pager.layoutInfo.pageSize - position

    fun drag(distance: Float) {
        if (!dragging) {
            animation?.cancel()
            dragStart = position
            dragging = true
        }
        val width = pager.layoutInfo.pageSize.toFloat()
        // One adjacent destination per gesture, with a hard stop at sequence endpoints.
        val target = (dragStart - distance).coerceIn(
            maxOf(0f, (selectedPage - 1) * width),
            minOf((pager.pageCount - 1) * width, (selectedPage + 1) * width),
        )
        pager.dispatchRawDelta(target - position)
    }

    fun finish(committed: Boolean, velocity: Float) {
        dragging = false
        releaseVelocity = -velocity // finger coordinates -> scroll coordinates
        if (!committed) settle(selectedPage, releaseVelocity)
    }

    fun enter(direction: Int) = select(selectedPage + direction)

    fun select(page: Int) {
        dragging = false
        selectedPage = page.coerceIn(0, pager.pageCount - 1)
        // Preserve the exact scroll position. Changing selection never moves pixels.
        settle(selectedPage, releaseVelocity)
        releaseVelocity = 0f
    }

    private fun settle(page: Int, velocity: Float) {
        animation?.cancel()
        animation = scope.launch {
            pager.scroll {
                val distance = page * pager.layoutInfo.pageSize - position
                var previous = 0f
                animate(
                    initialValue = 0f,
                    targetValue = distance,
                    // Keep extreme flicks from visually overshooting into a third page.
                    initialVelocity = velocity.coerceIn(-abs(distance) * 18f, abs(distance) * 18f),
                    animationSpec = spring(dampingRatio = 1f, stiffness = 650f, visibilityThreshold = 0.5f),
                ) { value, _ ->
                    scrollBy(value - previous)
                    previous = value
                }
            }
        }
    }
}

@Composable
fun rememberScreenSwipeMotion(initialPage: Int = 0, pageCount: Int = 4): ScreenSwipeMotion {
    val scope = rememberCoroutineScope()
    // The caller restores the committed destination. The viewport offset is transient.
    val pager = remember(pageCount) { PagerState(currentPage = initialPage, pageCount = { pageCount }) }
    return remember(scope, pager) { ScreenSwipeMotion(pager, scope, initialPage) }
}
