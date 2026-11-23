package com.ascend.mavlab.core.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Prepared adjacent UI shells; inactive previews cannot control flight or handle Android Back. */
@Composable
fun ScreenSwipePager(
    motion: ScreenSwipeMotion,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int, active: Boolean, settled: Boolean) -> Unit,
) {
    HorizontalPager(
        state = motion.pager,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false, // Explicit passive-area detector is the sole gesture owner.
        // Prepare the next shell before touch-down, rather than doing cold composition
        // on the first drag frame. Its lifecycle stays CREATED and SIM stays unstarted.
        beyondViewportPageCount = 1,
        key = { it },
    ) { page ->
        val active = page == motion.selectedPage
        val settled = active && motion.pager.currentPage == page &&
            !motion.pager.isScrollInProgress && kotlin.math.abs(motion.pager.currentPageOffsetFraction) < .0001f
        val parent = LocalLifecycleOwner.current
        val owner = remember { PreviewLifecycleOwner() }
        DisposableEffect(parent, active) {
            fun update() {
                owner.registry.currentState = if (active) parent.lifecycle.currentState
                    else minOf(parent.lifecycle.currentState, Lifecycle.State.CREATED)
            }
            val observer = LifecycleEventObserver { _, _ -> update() }
            parent.lifecycle.addObserver(observer)
            update()
            onDispose { parent.lifecycle.removeObserver(observer) }
        }
        DisposableEffect(owner) {
            onDispose { owner.registry.currentState = Lifecycle.State.DESTROYED }
        }
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            val protection = if (active) Modifier else Modifier.clearAndSetSemantics { }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            // Canvas coordinates (SIM grid/trail) can extend outside their own
            // layout. Clip each page, not just the overall viewport, so prepared
            // neighbours cannot draw a stray line over the selected surface.
            Box(Modifier.fillMaxSize().clipToBounds().then(KeepRelocationInsidePage).then(protection)) { content(page, active, settled) }
        }
    }
}

// Page-local scrollables handle relocation before it reaches this navigation boundary.
private object KeepRelocationInsidePage : ModifierNodeElement<PageRelocationBoundary>() {
    override fun create() = PageRelocationBoundary()
    override fun update(node: PageRelocationBoundary) = Unit
    override fun equals(other: Any?) = this === other
    override fun hashCode() = javaClass.hashCode()
}

private class PageRelocationBoundary : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) = Unit
}

private class PreviewLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}
