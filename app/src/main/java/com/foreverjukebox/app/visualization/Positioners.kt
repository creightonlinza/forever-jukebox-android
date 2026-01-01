package com.foreverjukebox.app.visualization

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class VizPoint(val x: Float, val y: Float)

typealias Positioner = (count: Int, width: Float, height: Float) -> List<VizPoint>

val positioners: List<Positioner> = listOf(
    { count, width, height ->
        val radius = min(width, height) * 0.4f
        val cx = width / 2f
        val cy = height / 2f
        (0 until count).map { i ->
            val angle = (i.toDouble() / count) * Math.PI * 2 - Math.PI / 2
            VizPoint(
                x = (cx + cos(angle) * radius).toFloat(),
                y = (cy + sin(angle) * radius).toFloat()
            )
        }
    },
    { count, width, height ->
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) * 0.42f
        val minRadius = min(width, height) * 0.08f
        val turns = 3
        (0 until count).map { i ->
            val t = i.toDouble() / count
            val angle = t * Math.PI * 2 * turns - Math.PI / 2
            val radius = minRadius + (maxRadius - minRadius) * t.toFloat()
            VizPoint(
                x = (cx + cos(angle) * radius).toFloat(),
                y = (cy + sin(angle) * radius).toFloat()
            )
        }
    },
    { count, width, height ->
        val cols = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
        val rows = ceil(count.toDouble() / cols).toInt().coerceAtLeast(1)
        val padding = 40f
        val gridW = width - padding * 2
        val gridH = height - padding * 2
        (0 until count).map { i ->
            val col = i % cols
            val row = i / cols
            val x = padding + (col / maxOf(1, cols - 1).toFloat()) * gridW
            val y = padding + (row / maxOf(1, rows - 1).toFloat()) * gridH
            VizPoint(x = x, y = y)
        }
    },
    { count, width, height ->
        val padding = 40f
        val amp = height * 0.25f
        val center = height / 2f
        val span = width - padding * 2
        val waveTurns = 3
        (0 until count).map { i ->
            val t = i.toFloat() / maxOf(1, count - 1)
            VizPoint(
                x = padding + span * t,
                y = center + (sin(t * Math.PI * 2 * waveTurns) * amp).toFloat()
            )
        }
    },
    { count, width, height ->
        val cx = width / 2f
        val cy = height / 2f
        val ampX = width * 0.35f
        val ampY = height * 0.25f
        (0 until count).map { i ->
            val t = (i.toDouble() / count) * Math.PI * 2
            VizPoint(
                x = (cx + sin(t) * ampX).toFloat(),
                y = (cy + sin(t * 2) * ampY).toFloat()
            )
        }
    },
    { count, width, height ->
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) * 0.42f
        val minRadius = min(width, height) * 0.08f
        val goldenAngle = Math.PI * (3 - sqrt(5.0))
        (0 until count).map { i ->
            val t = i.toDouble() / maxOf(1, count - 1)
            val angle = i * goldenAngle
            val radius = minRadius + (maxRadius - minRadius) * sqrt(t).toFloat()
            val wobble = 0.06 * sin(i * 12.9898) + 0.04 * cos(i * 4.1414)
            val r = radius * (1 + wobble).toFloat()
            VizPoint(
                x = (cx + cos(angle) * r).toFloat(),
                y = (cy + sin(angle) * r).toFloat()
            )
        }
    }
)
