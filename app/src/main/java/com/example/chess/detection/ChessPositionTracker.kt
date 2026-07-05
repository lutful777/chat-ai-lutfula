package com.example.chess.detection

import com.example.chess.domain.ChessColor
import kotlin.math.abs

class ChessPositionTracker {
    private var board: Array<CharArray>? = null
    private var lastObservation: CanonicalObservation? = null
    private var whiteAtBottom: Boolean? = null
    private var sideToMove: ChessColor = ChessColor.WHITE
    private var turnKnown = false
    private var castlingRights = "KQkq"
    private var enPassant = "-"
    private var halfmoveClock = 0
    private var fullmoveNumber = 1
    private var pendingSignature = ""
    private var stableFrames = 0

    fun update(observation: BoardVisualObservation): PositionTrackingResult {
        val orientation = observation.whiteAtBottom
            ?: return PositionTrackingResult.Waiting("Menentukan orientasi papan...")

        if (whiteAtBottom != null && whiteAtBottom != orientation) reset()
        whiteAtBottom = orientation

        val canonical = toCanonical(observation, orientation)
        val signature = canonical.signature()
        if (signature != pendingSignature) {
            pendingSignature = signature
            stableFrames = 1
            return PositionTrackingResult.Waiting("Menstabilkan pembacaan papan...")
        }
        stableFrames++
        if (stableFrames < 2) return PositionTrackingResult.Waiting("Menstabilkan pembacaan papan...")

        if (board == null) {
            if (!initializeFromStartingArea(canonical)) {
                lastObservation = canonical
                return PositionTrackingResult.Waiting(
                    "Posisi awal belum dikenali. Aktifkan asisten sebelum langkah pertama."
                )
            }
            lastObservation = canonical
            return if (turnKnown) ready(observation.geometry) else {
                PositionTrackingResult.Waiting("Menunggu satu langkah agar giliran dapat ditentukan...")
            }
        }

        val previous = lastObservation
        if (previous == null) {
            lastObservation = canonical
            return PositionTrackingResult.Waiting("Menyinkronkan posisi...")
        }

        val changes = findChanges(previous, canonical)
        if (changes.isEmpty()) {
            return if (turnKnown) ready(observation.geometry) else {
                PositionTrackingResult.Waiting("Menunggu perubahan posisi...")
            }
        }

        val applied = applyVisualMove(previous, canonical, changes)
        lastObservation = canonical
        if (!applied) {
            if (changes.size > 6) {
                resetPositionOnly()
                return PositionTrackingResult.Lost("Posisi berubah terlalu banyak; sinkronisasi diulang.")
            }
            return PositionTrackingResult.Waiting("Menunggu posisi papan yang stabil...")
        }

        return ready(observation.geometry)
    }

    fun reset() {
        whiteAtBottom = null
        resetPositionOnly()
    }

    private fun resetPositionOnly() {
        board = null
        lastObservation = null
        sideToMove = ChessColor.WHITE
        turnKnown = false
        castlingRights = "KQkq"
        enPassant = "-"
        halfmoveClock = 0
        fullmoveNumber = 1
        pendingSignature = ""
        stableFrames = 0
    }

    private fun initializeFromStartingArea(observation: CanonicalObservation): Boolean {
        val occupied = observation.occupiedCount()
        val middleOccupied = (2..5).sumOf { rank -> observation.occupied[rank].count { it } }
        val homeOccupied = listOf(0, 1, 6, 7).sumOf { rank -> observation.occupied[rank].count { it } }
        if (occupied < 26 || homeOccupied < 25 || middleOccupied > 6) return false

        val initial = startingBoard()
        val missing = mutableListOf<Square>()
        val extras = mutableListOf<Square>()

        for (rank in 0 until 8) {
            for (file in 0 until 8) {
                val expected = initial[rank][file]
                val observed = observation.occupied[rank][file]
                if (expected != '.' && !observed) missing += Square(file, rank)
                if (expected == '.' && observed) extras += Square(file, rank)
            }
        }

        if (missing.size != extras.size || missing.size > 4) return false
        val reconstructed = startingBoard()
        val movedColors = mutableListOf<ChessColor>()

        for (target in extras) {
            val targetColor = observation.colors[target.rank][target.file]
            val candidate = missing
                .filter { source ->
                    val piece = reconstructed[source.rank][source.file]
                    piece != '.' && (targetColor == null || colorOf(piece) == targetColor) &&
                        pseudoLegal(piece, source, target)
                }
                .minByOrNull { source -> moveCost(reconstructed[source.rank][source.file], source, target) }
                ?: return false

            val piece = reconstructed[candidate.rank][candidate.file]
            reconstructed[candidate.rank][candidate.file] = '.'
            reconstructed[target.rank][target.file] = piece
            missing.remove(candidate)
            movedColors += colorOf(piece)
        }

        board = reconstructed
        turnKnown = movedColors.size <= 1
        sideToMove = when {
            movedColors.isEmpty() -> ChessColor.WHITE
            movedColors.size == 1 -> opposite(movedColors.first())
            else -> ChessColor.WHITE
        }
        if (movedColors.size == 1 && movedColors.first() == ChessColor.BLACK) fullmoveNumber = 2
        return true
    }

    private fun applyVisualMove(
        previous: CanonicalObservation,
        current: CanonicalObservation,
        changes: List<Square>
    ): Boolean {
        val currentBoard = board ?: return false
        val emptied = changes.filter { previous.occupied[it.rank][it.file] && !current.occupied[it.rank][it.file] }
        val filled = changes.filter { !previous.occupied[it.rank][it.file] && current.occupied[it.rank][it.file] }
        val recolored = changes.filter {
            previous.occupied[it.rank][it.file] && current.occupied[it.rank][it.file] &&
                previous.colors[it.rank][it.file] != null && current.colors[it.rank][it.file] != null &&
                previous.colors[it.rank][it.file] != current.colors[it.rank][it.file]
        }

        if (emptied.size == 2 && filled.size == 2) {
            return applyCastling(currentBoard, emptied, filled)
        }

        val targetCandidates = filled + recolored
        if (targetCandidates.size != 1) return false
        val target = targetCandidates.first()
        val targetColor = current.colors[target.rank][target.file]

        val source = emptied.firstOrNull { square ->
            val piece = currentBoard[square.rank][square.file]
            piece != '.' && (targetColor == null || colorOf(piece) == targetColor) &&
                pseudoLegal(piece, square, target)
        } ?: return false

        val piece = currentBoard[source.rank][source.file]
        val mover = colorOf(piece)
        val captured = currentBoard[target.rank][target.file]

        currentBoard[source.rank][source.file] = '.'
        currentBoard[target.rank][target.file] = promoteIfNeeded(piece, target.rank)

        if (piece.lowercaseChar() == 'p' && source.file != target.file && captured == '.') {
            val capturedRank = if (mover == ChessColor.WHITE) target.rank - 1 else target.rank + 1
            if (capturedRank in 0..7) currentBoard[capturedRank][target.file] = '.'
        }

        updateCastlingRights(piece, source, target, captured)
        enPassant = if (piece.lowercaseChar() == 'p' && abs(target.rank - source.rank) == 2) {
            squareName(source.file, (source.rank + target.rank) / 2)
        } else {
            "-"
        }
        halfmoveClock = if (piece.lowercaseChar() == 'p' || captured != '.') 0 else halfmoveClock + 1
        if (mover == ChessColor.BLACK) fullmoveNumber++
        sideToMove = opposite(mover)
        turnKnown = true
        return true
    }

    private fun applyCastling(
        currentBoard: Array<CharArray>,
        emptied: List<Square>,
        filled: List<Square>
    ): Boolean {
        val kingSource = emptied.firstOrNull { currentBoard[it.rank][it.file].lowercaseChar() == 'k' }
            ?: return false
        val rookSource = emptied.firstOrNull { currentBoard[it.rank][it.file].lowercaseChar() == 'r' }
            ?: return false
        val color = colorOf(currentBoard[kingSource.rank][kingSource.file])
        val kingTarget = filled.firstOrNull { abs(it.file - kingSource.file) == 2 } ?: return false
        val rookTarget = filled.firstOrNull { it != kingTarget } ?: return false

        currentBoard[kingTarget.rank][kingTarget.file] = currentBoard[kingSource.rank][kingSource.file]
        currentBoard[rookTarget.rank][rookTarget.file] = currentBoard[rookSource.rank][rookSource.file]
        currentBoard[kingSource.rank][kingSource.file] = '.'
        currentBoard[rookSource.rank][rookSource.file] = '.'
        castlingRights = if (color == ChessColor.WHITE) {
            castlingRights.replace("K", "").replace("Q", "")
        } else {
            castlingRights.replace("k", "").replace("q", "")
        }
        enPassant = "-"
        halfmoveClock++
        if (color == ChessColor.BLACK) fullmoveNumber++
        sideToMove = opposite(color)
        turnKnown = true
        return true
    }

    private fun ready(geometry: BoardGeometry): PositionTrackingResult.Ready {
        val orientation = whiteAtBottom ?: true
        return PositionTrackingResult.Ready(
            fen = toFen(),
            bottomSide = if (orientation) ChessColor.WHITE else ChessColor.BLACK,
            whiteAtBottom = orientation,
            geometry = geometry
        )
    }

    private fun toFen(): String {
        val currentBoard = board ?: return ""
        val placement = buildString {
            for (rank in 7 downTo 0) {
                var empty = 0
                for (file in 0 until 8) {
                    val piece = currentBoard[rank][file]
                    if (piece == '.') {
                        empty++
                    } else {
                        if (empty > 0) append(empty)
                        empty = 0
                        append(piece)
                    }
                }
                if (empty > 0) append(empty)
                if (rank > 0) append('/')
            }
        }
        val turn = if (sideToMove == ChessColor.WHITE) "w" else "b"
        val castling = castlingRights.ifBlank { "-" }
        return "$placement $turn $castling $enPassant $halfmoveClock $fullmoveNumber"
    }

    private fun toCanonical(observation: BoardVisualObservation, whiteAtBottom: Boolean): CanonicalObservation {
        val occupied = Array(8) { BooleanArray(8) }
        val colors = Array(8) { arrayOfNulls<ChessColor>(8) }
        for (screenRow in 0 until 8) {
            for (screenColumn in 0 until 8) {
                val file = if (whiteAtBottom) screenColumn else 7 - screenColumn
                val rank = if (whiteAtBottom) 7 - screenRow else screenRow
                val square = observation.squares[screenRow][screenColumn]
                occupied[rank][file] = square.occupied
                colors[rank][file] = square.pieceColor
            }
        }
        return CanonicalObservation(occupied, colors)
    }

    private fun findChanges(previous: CanonicalObservation, current: CanonicalObservation): List<Square> {
        val changes = mutableListOf<Square>()
        for (rank in 0 until 8) {
            for (file in 0 until 8) {
                val occupancyChanged = previous.occupied[rank][file] != current.occupied[rank][file]
                val colorChanged = previous.colors[rank][file] != null && current.colors[rank][file] != null &&
                    previous.colors[rank][file] != current.colors[rank][file]
                if (occupancyChanged || colorChanged) changes += Square(file, rank)
            }
        }
        return changes
    }

    private fun startingBoard(): Array<CharArray> = arrayOf(
        charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R'),
        charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
        CharArray(8) { '.' }, CharArray(8) { '.' }, CharArray(8) { '.' }, CharArray(8) { '.' },
        charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
        charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')
    )

    private fun pseudoLegal(piece: Char, source: Square, target: Square): Boolean {
        val dx = abs(target.file - source.file)
        val dy = target.rank - source.rank
        return when (piece.lowercaseChar()) {
            'p' -> {
                val direction = if (piece.isUpperCase()) 1 else -1
                dy == direction && dx <= 1 || dy == 2 * direction && dx == 0
            }
            'n' -> dx * abs(dy) == 2
            'b' -> dx == abs(dy)
            'r' -> dx == 0 || dy == 0
            'q' -> dx == 0 || dy == 0 || dx == abs(dy)
            'k' -> dx <= 2 && abs(dy) <= 1
            else -> false
        }
    }

    private fun moveCost(piece: Char, source: Square, target: Square): Int =
        abs(target.file - source.file) + abs(target.rank - source.rank) +
            if (piece.lowercaseChar() == 'q' || piece.lowercaseChar() == 'k') 2 else 0

    private fun promoteIfNeeded(piece: Char, rank: Int): Char {
        if (piece == 'P' && rank == 7) return 'Q'
        if (piece == 'p' && rank == 0) return 'q'
        return piece
    }

    private fun updateCastlingRights(piece: Char, source: Square, target: Square, captured: Char) {
        when (piece) {
            'K' -> castlingRights = castlingRights.replace("K", "").replace("Q", "")
            'k' -> castlingRights = castlingRights.replace("k", "").replace("q", "")
            'R' -> if (source == Square(0, 0)) castlingRights = castlingRights.replace("Q", "")
                else if (source == Square(7, 0)) castlingRights = castlingRights.replace("K", "")
            'r' -> if (source == Square(0, 7)) castlingRights = castlingRights.replace("q", "")
                else if (source == Square(7, 7)) castlingRights = castlingRights.replace("k", "")
        }
        if (captured == 'R' && target == Square(0, 0)) castlingRights = castlingRights.replace("Q", "")
        if (captured == 'R' && target == Square(7, 0)) castlingRights = castlingRights.replace("K", "")
        if (captured == 'r' && target == Square(0, 7)) castlingRights = castlingRights.replace("q", "")
        if (captured == 'r' && target == Square(7, 7)) castlingRights = castlingRights.replace("k", "")
    }

    private fun colorOf(piece: Char): ChessColor = if (piece.isUpperCase()) ChessColor.WHITE else ChessColor.BLACK
    private fun opposite(color: ChessColor): ChessColor = if (color == ChessColor.WHITE) ChessColor.BLACK else ChessColor.WHITE
    private fun squareName(file: Int, rank: Int): String = "${('a'.code + file).toChar()}${rank + 1}"

    private data class Square(val file: Int, val rank: Int)
    private data class CanonicalObservation(
        val occupied: Array<BooleanArray>,
        val colors: Array<Array<ChessColor?>>
    ) {
        fun occupiedCount(): Int = occupied.sumOf { row -> row.count { it } }
        fun signature(): String = buildString(128) {
            for (rank in 0 until 8) {
                for (file in 0 until 8) {
                    append(if (occupied[rank][file]) '1' else '0')
                    append(
                        when (colors[rank][file]) {
                            ChessColor.WHITE -> 'W'
                            ChessColor.BLACK -> 'B'
                            null -> '-'
                        }
                    )
                }
            }
        }
    }
}

sealed interface PositionTrackingResult {
    data class Ready(
        val fen: String,
        val bottomSide: ChessColor,
        val whiteAtBottom: Boolean,
        val geometry: BoardGeometry
    ) : PositionTrackingResult

    data class Waiting(val message: String) : PositionTrackingResult
    data class Lost(val message: String) : PositionTrackingResult
}
