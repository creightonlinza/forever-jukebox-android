package com.foreverjukebox.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.ui.LocalThemeTokens
import com.foreverjukebox.app.ui.PillShape
import com.foreverjukebox.app.ui.SmallButtonHeight
import com.foreverjukebox.app.ui.SmallButtonPadding
import com.foreverjukebox.app.ui.SurfaceShape
import kotlin.math.min
import kotlin.math.roundToInt

private val CalloutMargin = 16.dp
private val CalloutMaxWidth = 320.dp
private val CaretSize = 8.dp
private val CaretInset = 20.dp
private val TargetPadding = 6.dp
private val TargetCorner = 10.dp
private val CalloutTagGap = 4.dp
private val CalloutTagSpace = 30.dp
private const val SCRIM_ALPHA = 0.72f
private val NoHole = { Rect.Zero }
private const val CALLOUT_BORDER_ALPHA = 0.6f

/**
 * The active step: a dark scrim with a glowing cutout around [target], a tag under each named
 * control in [callouts], and a card with a caret beside the target. The cutout glides from one
 * target to the next; its position is read only in layout and drawing, so the glide does not
 * recompose the card.
 */
@Suppress("LongParameterList")
@Composable
internal fun OnboardingOverlay(
    step: OnboardingStep,
    callouts: List<Pair<OnboardingCallout, Rect>>,
    stepNumber: Int,
    stepCount: Int,
    target: Rect,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onCalloutPlaced: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val animatedHole = animateValueAsState(
        targetValue = target.inflate(with(density) { TargetPadding.toPx() }),
        typeConverter = Rect.VectorConverter,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "onboardingTarget"
    )
    val hole = { animatedHole.value }
    // Named controls get a tag under each one, so the card sits below the tags.
    val tagSpace = if (callouts.isEmpty()) 0f else with(density) { CalloutTagSpace.toPx() }
    val anchor = { hole().let { it.copy(bottom = it.bottom + tagSpace) } }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(durationMillis = 220)) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
    ) {
        Spotlight(hole)
        BlockOutside(if (step.touchThrough) hole else NoHole)
        CalloutTags(callouts, top = { hole().bottom + with(density) { CalloutTagGap.toPx() } })
        AnchoredCallout(anchor = anchor, onCalloutPlaced = onCalloutPlaced) {
            StepCard(step, callouts.map { it.first }, stepNumber, stepCount, onNext, onSkip)
        }
    }
}

@Composable
private fun Spotlight(hole: () -> Rect) {
    val accent = LocalThemeTokens.current.accent
    val scrim = remember { Path().apply { fillType = PathFillType.EvenOdd } }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val rect = hole()
        val corner = TargetCorner.toPx()
        scrim.reset()
        scrim.addRect(size.toRect())
        scrim.addRoundRect(RoundRect(rect, CornerRadius(corner)))
        drawPath(scrim, Color.Black.copy(alpha = SCRIM_ALPHA))
        val glowStep = 3.dp.toPx()
        for (ring in 1..4) {
            val spread = glowStep * ring
            drawRoundRect(
                color = accent.copy(alpha = 0.22f / ring),
                topLeft = rect.topLeft - Offset(spread, spread),
                size = rect.inflate(spread).size,
                cornerRadius = CornerRadius(corner + spread),
                style = Stroke(width = glowStep)
            )
        }
        drawRoundRect(
            color = accent,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(corner),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun StepCard(
    step: OnboardingStep,
    callouts: List<OnboardingCallout>,
    stepNumber: Int,
    stepCount: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    val tokens = LocalThemeTokens.current
    Column(
        modifier = Modifier
            .background(tokens.panelSurface, SurfaceShape)
            .border(1.dp, tokens.accent.copy(alpha = CALLOUT_BORDER_ALPHA), SurfaceShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "STEP $stepNumber OF $stepCount",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.accent
        )
        AnimatedContent(
            targetState = step to callouts,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboardingStepText"
        ) { (shownStep, shownCallouts) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = shownStep.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.onBackground
                )
                Text(text = shownStep.body, style = MaterialTheme.typography.bodySmall, color = tokens.muted)
                CalloutLines(shownCallouts)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onSkip,
                colors = ButtonDefaults.textButtonColors(contentColor = tokens.muted)
            ) {
                Text("Skip tour", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onNext,
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text(
                    text = if (stepNumber >= stepCount) "Got it" else "Next",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** "Label: description" for each named control on screen. */
@Composable
private fun CalloutLines(callouts: List<OnboardingCallout>) {
    if (callouts.isEmpty()) return
    val tokens = LocalThemeTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        callouts.forEach { callout ->
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = tokens.accent, fontWeight = FontWeight.Bold)) {
                        append(callout.label)
                    }
                    append(": ${callout.description}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = tokens.muted
            )
        }
    }
}

/** A small label under each named control, centered on it and kept inside the screen. */
@Composable
private fun CalloutTags(callouts: List<Pair<OnboardingCallout, Rect>>, top: () -> Float) {
    if (callouts.isEmpty()) return
    val accent = LocalThemeTokens.current.accent
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Layout(
        content = {
            callouts.forEach { (callout, _) ->
                Text(
                    text = callout.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = onAccent,
                    modifier = Modifier
                        .background(accent, PillShape)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val edge = 8.dp.toPx()
        val lefts = spreadTags(
            centers = callouts.map { it.second.center.x - edge },
            widths = placeables.map { it.width.toFloat() },
            containerWidth = constraints.maxWidth - 2 * edge,
            gap = 4.dp.toPx()
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val y = top().roundToInt()
            placeables.forEachIndexed { index, placeable ->
                placeable.place((lefts[index] + edge).roundToInt(), y)
            }
        }
    }
}

/**
 * Swallows touches everywhere except the [hole], so the highlighted control stays usable. Four
 * boxes rather than one, because a single full-screen touch layer would also cover the hole.
 */
@Composable
private fun BlockOutside(hole: () -> Rect) {
    Layout(
        content = { repeat(4) { Box(modifier = Modifier.swallowPointer()) } },
        modifier = Modifier.fillMaxSize()
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val rect = hole()
        val left = rect.left.roundToInt().coerceIn(0, width)
        val top = rect.top.roundToInt().coerceIn(0, height)
        val right = rect.right.roundToInt().coerceIn(left, width)
        val bottom = rect.bottom.roundToInt().coerceIn(top, height)
        val regions = arrayOf(
            IntRect(0, 0, width, top),
            IntRect(0, bottom, width, height),
            IntRect(0, top, left, bottom),
            IntRect(right, top, width, bottom)
        )
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixed(regions[index].width, regions[index].height))
        }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(regions[index].left, regions[index].top)
            }
        }
    }
}

private fun Modifier.swallowPointer(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/** Places [content] beside [anchor] with a caret that points at the anchor's center. */
@Composable
private fun AnchoredCallout(
    anchor: () -> Rect,
    onCalloutPlaced: (Rect) -> Unit,
    content: @Composable () -> Unit
) {
    val tokens = LocalThemeTokens.current
    val caretBorder = tokens.accent.copy(alpha = CALLOUT_BORDER_ALPHA)
    val safeArea = WindowInsets.safeDrawing
    var placement by remember { mutableStateOf<CalloutPlacement?>(null) }
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .onGloballyPositioned { onCalloutPlaced(it.boundsInWindow()) }
                    // Stops taps on the card's text from reaching a control underneath it.
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    .drawWithContent {
                        drawContent()
                        placement?.let { drawCaret(it, tokens.panelSurface, caretBorder) }
                    }
            ) {
                content()
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { measurables, constraints ->
        // The card keeps clear of the system bars and cutouts as well as the screen edge.
        val insetLeft = safeArea.getLeft(this, layoutDirection)
        val insetTop = safeArea.getTop(this)
        val safeWidth = constraints.maxWidth - insetLeft - safeArea.getRight(this, layoutDirection)
        val safeHeight = constraints.maxHeight - insetTop - safeArea.getBottom(this)
        val margin = CalloutMargin.toPx()
        val width = min(safeWidth - 2 * margin, CalloutMaxWidth.toPx()).roundToInt().coerceAtLeast(0)
        val placeable = measurables.first().measure(Constraints(maxWidth = width))
        val placed = placeCallout(
            containerWidth = safeWidth.toFloat(),
            containerHeight = safeHeight.toFloat(),
            anchor = anchor().translate(-insetLeft.toFloat(), -insetTop.toFloat()),
            calloutWidth = placeable.width.toFloat(),
            calloutHeight = placeable.height.toFloat(),
            margin = margin,
            gap = CaretSize.toPx() + 2.dp.toPx(),
            caretInset = CaretInset.toPx()
        )
        if (placement != placed) placement = placed
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(placed.x.roundToInt() + insetLeft, placed.y.roundToInt() + insetTop)
        }
    }
}

/** Drawn over the callout so the fill covers the callout's border where the caret joins it. */
private fun DrawScope.drawCaret(placement: CalloutPlacement, color: Color, border: Color) {
    val caret = CaretSize.toPx()
    val stroke = 1.dp.toPx()
    val edgeY = if (placement.below) stroke else size.height - stroke
    val tipY = if (placement.below) -caret else size.height + caret
    val left = Offset(placement.caretX - caret, edgeY)
    val tip = Offset(placement.caretX, tipY)
    val right = Offset(placement.caretX + caret, edgeY)
    val path = Path().apply {
        moveTo(left.x, left.y)
        lineTo(tip.x, tip.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(path, color)
    drawLine(border, left, tip, strokeWidth = stroke)
    drawLine(border, tip, right, strokeWidth = stroke)
}
