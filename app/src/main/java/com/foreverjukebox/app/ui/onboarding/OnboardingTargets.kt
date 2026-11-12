package com.foreverjukebox.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.isSpecified

/** Window bounds of the tour targets on screen, plus the readiness of the playback screen. */
@Stable
class OnboardingRegistry {
    private val bounds = mutableStateMapOf<OnboardingTarget, Rect>()

    var playbackReady by mutableStateOf(false)

    /** A live view of the targets on screen; reading it inside a snapshot observes placement. */
    val placedTargets: Set<OnboardingTarget> get() = bounds.keys

    fun boundsOf(target: OnboardingTarget): Rect? = bounds[target]

    internal fun place(target: OnboardingTarget, rect: Rect) {
        if (rect.isEmpty) {
            bounds.remove(target)
        } else if (bounds[target] != rect) {
            bounds[target] = rect
        }
    }

    internal fun remove(target: OnboardingTarget) {
        bounds.remove(target)
    }
}

/** Null where no flavor hosts a tour, which turns every onboarding hook into a no-op. */
val LocalOnboardingRegistry = staticCompositionLocalOf<OnboardingRegistry?> { null }

/**
 * Reports this element's window bounds as [target] while it is attached. [visualSize] shrinks the
 * reported bounds around their center, for a Material button whose minimum touch area is larger
 * than what it draws.
 */
fun Modifier.onboardingTarget(
    target: OnboardingTarget,
    visualSize: DpSize = DpSize.Unspecified
): Modifier = this then OnboardingTargetElement(target, visualSize)

/** Marks the playback screen as interactive, so its tour may start. */
@Composable
fun OnboardingPlaybackReadyEffect(ready: Boolean) {
    val registry = LocalOnboardingRegistry.current ?: return
    DisposableEffect(registry, ready) {
        registry.playbackReady = ready
        onDispose { registry.playbackReady = false }
    }
}

private data class OnboardingTargetElement(
    val target: OnboardingTarget,
    val visualSize: DpSize
) : ModifierNodeElement<OnboardingTargetNode>() {
    override fun create() = OnboardingTargetNode(target, visualSize)

    override fun update(node: OnboardingTargetNode) {
        if (node.target == target && node.visualSize == visualSize) return
        node.withdraw()
        node.target = target
        node.visualSize = visualSize
        node.placeAgain()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "onboardingTarget"
        properties["target"] = target
    }
}

private fun Rect.shrinkTo(visualSize: DpSize, density: Density): Rect {
    fun half(limit: Dp, full: Float) = (if (limit.isSpecified) minOf(full, with(density) { limit.toPx() }) else full) / 2f
    val halfWidth = half(visualSize.width, width)
    val halfHeight = half(visualSize.height, height)
    return Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
}

private class OnboardingTargetNode(
    var target: OnboardingTarget,
    var visualSize: DpSize
) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    // The registry this node last reported to, so detaching or retargeting can withdraw the entry.
    private var placedIn: OnboardingRegistry? = null

    // Kept so a changed target or size is placed at once; layout may not move to report it again.
    private var lastCoordinates: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        lastCoordinates = coordinates
        placeAgain()
    }

    /** Reads the registry at time of use; where no flavor hosts a tour it is null and nothing happens. */
    fun placeAgain() {
        if (!isAttached) return
        val coordinates = lastCoordinates?.takeIf { it.isAttached } ?: return
        val registry = currentValueOf(LocalOnboardingRegistry)
        if (registry !== placedIn) withdraw()
        if (registry == null) return
        placedIn = registry
        registry.place(target, coordinates.boundsInWindow().shrinkTo(visualSize, currentValueOf(LocalDensity)))
    }

    fun withdraw() {
        placedIn?.remove(target)
        placedIn = null
    }

    override fun onDetach() {
        withdraw()
        lastCoordinates = null
    }
}
