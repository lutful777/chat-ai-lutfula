package com.example.chess.detection

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.min
import kotlin.math.sqrt

enum class BoardOrientation { WHITE_BOTTOM, BLACK_BOTTOM }

data class BoardDetectionResult(
    val bounds: Rect,
    val orientation: BoardOrientation,
    val occupied: Array<BooleanArray>,
    val signatures: Array<IntArray>,
    val confidence: Float
)

class ChessBoardDetector {

    fun detectBoard(bitmap: Bitmap): BoardDetectionResult? {
        if (bitmap.width < 160 || bitmap.height < 160) return null

        val minDimension = min(bitmap.width, bitmap.height)
        var best: Candidate? = null
        val fractions = floatArrayOf(1.0f, 0.92f, 0.84f, 0.76f, 0.68f, 0.60f)

        for (fraction in fractions) {
            val rawSize = (minDimension * fraction).toInt()
            val size = rawSize - rawSize % 8
            if (size < 160) continue
            val maxX = bitmap.width - size
            val maxY = bitmap.height - size
            val xSteps = if (maxX == 0) 1 else 5
            val ySteps = if (maxY == 0) 1 else 12

            for (xi in 0 until xSteps) {
                val left = if (xSteps == 1) 0 else maxX * xi / (xSteps - 1)
                for (yi in 0 until ySteps) {
                    val top = if (ySteps == 1) 0 else maxY * yi / (ySteps - 1)
                    val candidate = scoreCandidate(bitmap, left, top, size)
                    if (best == null || candidate.score > best!!.score) best = candidate
                }
            }
        }

        val selected = best ?: return null
        if (selected.score < MIN_BOARD_SCORE) return null

        val occupied = Array(8) { BooleanArray(8) }
        val signatures = Array(8) { IntArray(8) }
        val square = selected.size / 8f

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val x0 = selected.left + col * square
                val y0 = selected.top + row * square
                val background = sampleSquareBackground(bitmap, x0, y0, square)
                val centreSamples = sampleCentre(bitmap, x0, y0, square)
                val centre = average(centreSamples)
                val distance = colourDistance(background, centre)
                val spread = colourSpread(centreSamples, centre)
                occupied[row][col] = distance > OCCUPANCY_DISTANCE || spread > OCCUPANCY_SPREAD
                signatures[row][col] = signature(centreSamples)
            }
        }

        val orientation = estimateOrientation(bitmap, selected, occupied)
        val confidence = ((selected.score - MIN_BOARD_SCORE) / 70.0).toFloat().coerceIn(0f, 1f)
        return BoardDetectionResult(
            bounds = Rect(selected.left, selected.top, selected.left + selected.size, selected.top + selected.size),
            orientation = orientation,
            occupied = occupied,
            signatures = signatures,
            confidence = confidence
        )
    }

    private fun scoreCandidate(bitmap: Bitmap, left: Int, top: Int, size: Int): Candidate {
        val square = size / 8f
        val lightSamples = ArrayList<Rgb>(32)
        val darkSamples = ArrayList<Rgb>(32)
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val colour = sampleSquareBackground(
                    bitmap,
                    left + col * square,
                    top + row * square,
                    square
                )
                if ((row + col) % 2 == 0) lightSamples += colour else darkSamples += colour
            }
        }

        val first = average(lightSamples)
        val second = average(darkSamples)
        val separation = colourDistance(first, second)
        val variancePenalty = (colourSpread(lightSamples, first) + colourSpread(darkSamples, second)) * 0.55
        return Candidate(left, top, size, separation - variancePenalty)
    }

    private fun estimateOrientation(
        bitmap: Bitmap,
        candidate: Candidate,
        occupied: Array<BooleanArray>
    ): BoardOrientation {
        val square = candidate.size / 8f
        var topLum = 0.0
        var topCount = 0
        var bottomLum = 0.0
        var bottomCount = 0

        for (row in intArrayOf(0, 1, 6, 7)) {
            for (col in 0 until 8) {
                if (!occupied[row][col]) continue
                val samples = sampleCentre(
                    bitmap,
                    candidate.left + col * square,
                    candidate.top + row * square,
                    square
                )
                val lum = luminance(average(samples))
                if (row <= 1) {
                    topLum += lum
                    topCount++
                } else {
                    bottomLum += lum
                    bottomCount++
                }
            }
        }

        if (topCount == 0 || bottomCount == 0) return BoardOrientation.WHITE_BOTTOM
        return if (bottomLum / bottomCount >= topLum / topCount) {
            BoardOrientation.WHITE_BOTTOM
        } else {
            BoardOrientation.BLACK_BOTTOM
        }
    }

    private fun sampleSquareBackground(bitmap: Bitmap, x0: Float, y0: Float, size: Float): Rgb {
        val offsets = arrayOf(
            0.12f to 0.12f,
            0.88f to 0.12f,
            0.12f to 0.88f,
            0.88f to 0.88f
        )
        return average(offsets.map { (ox, oy) ->
            rgbAt(bitmap, (x0 + size * ox).toInt(), (y0 + size * oy).toInt())
        })
    }

    private fun sampleCentre(bitmap: Bitmap, x0: Float, y0: Float, size: Float): List<Rgb> {
        val samples = ArrayList<Rgb>(25)
        for (yi in 0 until 5) {
            for (xi in 0 until 5) {
                val ox = 0.25f + xi * 0.125f
                val oy = 0.25f + yi * 0.125f
                samples += rgbAt(bitmap, (x0 + size * ox).toInt(), (y0 + size * oy).toInt())
            }
        }
        return samples
    }

    private fun signature(samples: List<Rgb>): Int {
        var hash = 17
        for (sample in samples.filterIndexed { index, _ -> index % 3 == 0 }) {
            val r = (sample.r / 32).toInt().coerceIn(0, 7)
            val g = (sample.g / 32).toInt().coerceIn(0, 7)
            val b = (sample.b / 32).toInt().coerceIn(0, 7)
            hash = 31 * hash + (r shl 6) + (g shl 3) + b
        }
        return hash
    }

    private fun rgbAt(bitmap: Bitmap, x: Int, y: Int): Rgb {
        val safeX = x.coerceIn(0, bitmap.width - 1)
        val safeY = y.coerceIn(0, bitmap.height - 1)
        val colour = bitmap.getPixel(safeX, safeY)
        return Rgb(Color.red(colour).toDouble(), Color.green(colour).toDouble(), Color.blue(colour).toDouble())
    }

    private fun average(samples: List<Rgb>): Rgb {
        if (samples.isEmpty()) return Rgb(0.0, 0.0, 0.0)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (sample in samples) {
            r += sample.r
            g += sample.g
            b += sample.b
        }
        return Rgb(r / samples.size, g / samples.size, b / samples.size)
    }

    private fun colourSpread(samples: List<Rgb>, mean: Rgb): Double {
        if (samples.isEmpty()) return 0.0
        var total = 0.0
        for (sample in samples) total += colourDistance(sample, mean)
        return total / samples.size
    }

    private fun colourDistance(a: Rgb, b: Rgb): Double {
        val dr = a.r - b.r
        val dg = a.g - b.g
        val db = a.b - b.b
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun luminance(colour: Rgb): Double =
        0.2126 * colour.r + 0.7152 * colour.g + 0.0722 * colour.b

    private data class Candidate(val left: Int, val top: Int, val size: Int, val score: Double)
    private data class Rgb(val r: Double, val g: Double, val b: Double)

    companion object {
        private const val MIN_BOARD_SCORE = 18.0
        private const val OCCUPANCY_DISTANCE = 24.0
        private const val OCCUPANCY_SPREAD = 28.0
    }
}
