package com.example.chess.detection

import android.graphics.Bitmap
import com.example.chess.domain.ChessColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ChessPieceRecognizer {
    fun recognize(bitmap: Bitmap, geometry: BoardGeometry): BoardVisualObservation {
        val raw = Array(8) { Array(8) { RawSquare() } }
        val occupiedLumas = mutableListOf<Float>()
        val occupiedLocations = mutableListOf<Pair<Int, Int>>()
        var confidenceSum = 0f

        for (row in 0 until 8) {
            for (column in 0 until 8) {
                val square = inspectSquare(bitmap, geometry, column, row)
                raw[row][column] = square
                confidenceSum += square.confidence
                if (square.occupied) {
                    occupiedLumas += square.foregroundLuma
                    occupiedLocations += row to column
                }
            }
        }

        val clusters = clusterPieceLuminance(occupiedLumas)
        val visuals = Array(8) { row ->
            Array(8) { column ->
                val square = raw[row][column]
                val color = if (!square.occupied || clusters == null) {
                    null
                } else {
                    val distanceToDark = abs(square.foregroundLuma - clusters.first)
                    val distanceToLight = abs(square.foregroundLuma - clusters.second)
                    if (distanceToLight <= distanceToDark) ChessColor.WHITE else ChessColor.BLACK
                }
                SquareVisual(
                    occupied = square.occupied,
                    pieceColor = color,
                    confidence = square.confidence,
                    foregroundLuma = square.foregroundLuma
                )
            }
        }

        val whiteAtBottom = inferOrientation(visuals)
        return BoardVisualObservation(
            geometry = geometry,
            squares = visuals,
            whiteAtBottom = whiteAtBottom,
            confidence = (confidenceSum / 64f).coerceIn(0f, 1f)
        )
    }

    private fun inspectSquare(
        bitmap: Bitmap,
        geometry: BoardGeometry,
        column: Int,
        row: Int
    ): RawSquare {
        val squareSize = geometry.squareSize
        val left = geometry.left + column * squareSize
        val top = geometry.top + row * squareSize
        val sampleStep = max(1, (squareSize / 24f).roundToInt())

        val background = averageBackground(bitmap, left, top, squareSize)
        val backgroundLuma = luma(background[0], background[1], background[2])
        val startX = (left + squareSize * 0.12f).roundToInt()
        val endX = (left + squareSize * 0.88f).roundToInt()
        val startY = (top + squareSize * 0.10f).roundToInt()
        val endY = (top + squareSize * 0.90f).roundToInt()

        var sampleCount = 0
        var foregroundCount = 0
        var strongForegroundCount = 0
        var foregroundLumaSum = 0f
        var gradientCount = 0
        var gradientSamples = 0
        val threshold = 30f

        var y = startY
        while (y <= endY) {
            var x = startX
            while (x <= endX) {
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    val color = bitmap.getPixel(x, y)
                    val red = (color shr 16) and 0xff
                    val green = (color shr 8) and 0xff
                    val blue = color and 0xff
                    val distance = colorDistance(red.toFloat(), green.toFloat(), blue.toFloat(), background)
                    sampleCount++
                    if (distance > threshold) {
                        foregroundCount++
                        foregroundLumaSum += luma(red.toFloat(), green.toFloat(), blue.toFloat())
                    }
                    if (distance > 52f) strongForegroundCount++

                    val nextX = min(bitmap.width - 1, x + sampleStep)
                    val nextY = min(bitmap.height - 1, y + sampleStep)
                    val right = bitmap.getPixel(nextX, y.coerceIn(0, bitmap.height - 1))
                    val down = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), nextY)
                    val currentLuma = luma(red.toFloat(), green.toFloat(), blue.toFloat())
                    val rightLuma = luma(
                        ((right shr 16) and 0xff).toFloat(),
                        ((right shr 8) and 0xff).toFloat(),
                        (right and 0xff).toFloat()
                    )
                    val downLuma = luma(
                        ((down shr 16) and 0xff).toFloat(),
                        ((down shr 8) and 0xff).toFloat(),
                        (down and 0xff).toFloat()
                    )
                    gradientSamples += 2
                    if (abs(currentLuma - rightLuma) > 20f) gradientCount++
                    if (abs(currentLuma - downLuma) > 20f) gradientCount++
                }
                x += sampleStep
            }
            y += sampleStep
        }

        val foregroundRatio = foregroundCount.toFloat() / max(1, sampleCount)
        val strongRatio = strongForegroundCount.toFloat() / max(1, sampleCount)
        val gradientRatio = gradientCount.toFloat() / max(1, gradientSamples)
        val occupied = foregroundRatio > 0.105f && gradientRatio > 0.035f ||
            foregroundRatio > 0.16f || strongRatio > 0.075f
        val foregroundLuma = if (foregroundCount > 0) {
            foregroundLumaSum / foregroundCount
        } else {
            backgroundLuma
        }
        val confidence = if (occupied) {
            (foregroundRatio * 2.7f + gradientRatio * 1.8f + strongRatio * 1.6f).coerceIn(0f, 1f)
        } else {
            (1f - foregroundRatio * 3.2f).coerceIn(0f, 1f)
        }

        return RawSquare(occupied, foregroundLuma, confidence)
    }

    private fun averageBackground(bitmap: Bitmap, left: Float, top: Float, squareSize: Float): FloatArray {
        val offsets = arrayOf(
            0.12f to 0.12f,
            0.88f to 0.12f,
            0.12f to 0.88f,
            0.88f to 0.88f
        )
        var red = 0f
        var green = 0f
        var blue = 0f
        var count = 0
        val radius = max(1, (squareSize / 40f).roundToInt())

        for ((offsetX, offsetY) in offsets) {
            val centerX = (left + offsetX * squareSize).roundToInt()
            val centerY = (top + offsetY * squareSize).roundToInt()
            for (y in centerY - radius..centerY + radius) {
                for (x in centerX - radius..centerX + radius) {
                    if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) continue
                    val color = bitmap.getPixel(x, y)
                    red += (color shr 16) and 0xff
                    green += (color shr 8) and 0xff
                    blue += color and 0xff
                    count++
                }
            }
        }
        return floatArrayOf(red / max(1, count), green / max(1, count), blue / max(1, count))
    }

    private fun clusterPieceLuminance(values: List<Float>): Pair<Float, Float>? {
        if (values.size < 4) return null
        var dark = values.minOrNull() ?: return null
        var light = values.maxOrNull() ?: return null
        if (light - dark < 16f) return null

        repeat(8) {
            var darkSum = 0f
            var lightSum = 0f
            var darkCount = 0
            var lightCount = 0
            for (value in values) {
                if (abs(value - dark) <= abs(value - light)) {
                    darkSum += value
                    darkCount++
                } else {
                    lightSum += value
                    lightCount++
                }
            }
            if (darkCount > 0) dark = darkSum / darkCount
            if (lightCount > 0) light = lightSum / lightCount
        }
        return if (light - dark >= 14f) dark to light else null
    }

    private fun inferOrientation(squares: Array<Array<SquareVisual>>): Boolean? {
        var whiteTop = 0f
        var whiteBottom = 0f
        var blackTop = 0f
        var blackBottom = 0f
        for (row in 0 until 8) {
            for (column in 0 until 8) {
                val square = squares[row][column]
                when (square.pieceColor) {
                    ChessColor.WHITE -> if (row < 4) whiteTop += square.confidence else whiteBottom += square.confidence
                    ChessColor.BLACK -> if (row < 4) blackTop += square.confidence else blackBottom += square.confidence
                    null -> Unit
                }
            }
        }
        val whiteBottomScore = whiteBottom + blackTop
        val blackBottomScore = blackBottom + whiteTop
        val difference = abs(whiteBottomScore - blackBottomScore)
        if (difference < 2.2f) return null
        return whiteBottomScore > blackBottomScore
    }

    private fun luma(red: Float, green: Float, blue: Float): Float =
        0.2126f * red + 0.7152f * green + 0.0722f * blue

    private fun colorDistance(red: Float, green: Float, blue: Float, background: FloatArray): Float {
        val redDelta = red - background[0]
        val greenDelta = green - background[1]
        val blueDelta = blue - background[2]
        return sqrt(redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta)
    }

    private data class RawSquare(
        val occupied: Boolean = false,
        val foregroundLuma: Float = 0f,
        val confidence: Float = 0f
    )
}
