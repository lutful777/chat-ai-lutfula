package com.example.chess.detection

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ChessBoardDetector {
    fun detect(bitmap: Bitmap, previous: BoardGeometry? = null): BoardGeometry? {
        if (bitmap.width < 160 || bitmap.height < 160) return null

        val maxDimension = max(bitmap.width, bitmap.height)
        val scale = min(1f, 360f / maxDimension)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()),
                true
            )
        } else {
            bitmap
        }

        return try {
            val candidate = previous?.let { scoreNearPrevious(scaled, it, scale) }
                ?.takeIf { it.score >= 0.16f }
                ?: searchGlobally(scaled)

            candidate?.takeIf { it.score >= 0.12f }?.let {
                BoardGeometry(
                    left = (it.left / scale).roundToInt().coerceIn(0, bitmap.width - 1),
                    top = (it.top / scale).roundToInt().coerceIn(0, bitmap.height - 1),
                    size = (it.size / scale).roundToInt().coerceAtMost(min(bitmap.width, bitmap.height)),
                    confidence = it.score.coerceIn(0f, 1f)
                )
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun scoreNearPrevious(bitmap: Bitmap, previous: BoardGeometry, scale: Float): Candidate? {
        val baseLeft = (previous.left * scale).roundToInt()
        val baseTop = (previous.top * scale).roundToInt()
        val baseSize = (previous.size * scale).roundToInt()
        var best: Candidate? = null
        val sizeDelta = max(8, baseSize / 16)
        val positionDelta = max(12, baseSize / 12)

        for (size in (baseSize - sizeDelta)..(baseSize + sizeDelta) step max(2, sizeDelta / 4)) {
            if (size < 96 || size > min(bitmap.width, bitmap.height)) continue
            for (top in (baseTop - positionDelta)..(baseTop + positionDelta) step max(3, positionDelta / 5)) {
                for (left in (baseLeft - positionDelta)..(baseLeft + positionDelta) step max(3, positionDelta / 5)) {
                    val score = scoreCandidate(bitmap, left, top, size)
                    if (score > (best?.score ?: Float.NEGATIVE_INFINITY)) {
                        best = Candidate(left, top, size, score)
                    }
                }
            }
        }
        return best
    }

    private fun searchGlobally(bitmap: Bitmap): Candidate? {
        val minDimension = min(bitmap.width, bitmap.height)
        val minBoard = max(120, (minDimension * 0.42f).roundToInt())
        val maxBoard = (minDimension * 0.98f).roundToInt()
        var best: Candidate? = null
        var size = minBoard - (minBoard % 8)

        while (size <= maxBoard) {
            val square = size / 8
            val step = max(6, square / 2)
            val yLimit = bitmap.height - size
            val xLimit = bitmap.width - size
            var top = 0
            while (top <= yLimit) {
                var left = 0
                while (left <= xLimit) {
                    val score = scoreCandidate(bitmap, left, top, size)
                    if (score > (best?.score ?: Float.NEGATIVE_INFINITY)) {
                        best = Candidate(left, top, size, score)
                    }
                    left += step
                }
                top += step
            }
            size += max(8, square / 2) * 8 / 8
        }
        return best
    }

    private fun scoreCandidate(bitmap: Bitmap, left: Int, top: Int, size: Int): Float {
        if (left < 0 || top < 0 || left + size > bitmap.width || top + size > bitmap.height) {
            return Float.NEGATIVE_INFINITY
        }
        val square = size / 8f
        if (square < 12f) return Float.NEGATIVE_INFINITY

        val colors = Array(8) { Array(8) { FloatArray(3) } }
        val paritySums = Array(2) { FloatArray(3) }
        val parityCounts = IntArray(2)

        for (row in 0 until 8) {
            for (column in 0 until 8) {
                val color = sampleSquareBackground(bitmap, left, top, square, column, row)
                colors[row][column] = color
                val parity = (row + column) and 1
                paritySums[parity][0] += color[0]
                paritySums[parity][1] += color[1]
                paritySums[parity][2] += color[2]
                parityCounts[parity]++
            }
        }

        val means = Array(2) { parity ->
            floatArrayOf(
                paritySums[parity][0] / parityCounts[parity],
                paritySums[parity][1] / parityCounts[parity],
                paritySums[parity][2] / parityCounts[parity]
            )
        }
        val separation = colorDistance(means[0], means[1]) / 441.67f
        if (separation < 0.035f) return Float.NEGATIVE_INFINITY

        var within = 0f
        var adjacentAgreement = 0f
        var adjacentCount = 0
        for (row in 0 until 8) {
            for (column in 0 until 8) {
                val parity = (row + column) and 1
                within += colorDistance(colors[row][column], means[parity]) / 441.67f
                if (column < 7) {
                    adjacentAgreement += colorDistance(colors[row][column], colors[row][column + 1]) / 441.67f
                    adjacentCount++
                }
                if (row < 7) {
                    adjacentAgreement += colorDistance(colors[row][column], colors[row + 1][column]) / 441.67f
                    adjacentCount++
                }
            }
        }
        within /= 64f
        adjacentAgreement /= max(1, adjacentCount)

        val squarenessBonus = 0.03f
        return (separation * 1.7f + adjacentAgreement * 0.45f - within * 1.25f + squarenessBonus)
            .coerceIn(-1f, 1f)
    }

    private fun sampleSquareBackground(
        bitmap: Bitmap,
        boardLeft: Int,
        boardTop: Int,
        square: Float,
        column: Int,
        row: Int
    ): FloatArray {
        val offsets = arrayOf(
            0.18f to 0.18f,
            0.82f to 0.18f,
            0.18f to 0.82f,
            0.82f to 0.82f
        )
        var red = 0f
        var green = 0f
        var blue = 0f
        for ((offsetX, offsetY) in offsets) {
            val x = (boardLeft + (column + offsetX) * square).roundToInt().coerceIn(0, bitmap.width - 1)
            val y = (boardTop + (row + offsetY) * square).roundToInt().coerceIn(0, bitmap.height - 1)
            val color = bitmap.getPixel(x, y)
            red += (color shr 16) and 0xff
            green += (color shr 8) and 0xff
            blue += color and 0xff
        }
        return floatArrayOf(red / 4f, green / 4f, blue / 4f)
    }

    private fun colorDistance(first: FloatArray, second: FloatArray): Float {
        val red = first[0] - second[0]
        val green = first[1] - second[1]
        val blue = first[2] - second[2]
        return sqrt(red * red + green * green + blue * blue)
    }

    private data class Candidate(val left: Int, val top: Int, val size: Int, val score: Float)
}
