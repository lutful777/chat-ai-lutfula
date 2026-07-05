package com.example.chess.detection

import com.example.chess.domain.ChessColor
import com.example.chess.domain.ChessPiece
import com.example.chess.domain.ChessPieceType
import com.example.chess.fen.FenConverter
import kotlin.math.abs

sealed interface PositionTrackingResult {
    data class Waiting(val message: String) : PositionTrackingResult
    data class Position(val fen: String, val changed: Boolean) : PositionTrackingResult
}

/**
 * Tracks a game from the normal initial chess position. Piece identities are
 * retained from move history, so the image detector only needs occupancy and
 * visual-change information after the initial position has been confirmed.
 */
class ChessPositionTracker {
    private var board: Array<Array<ChessPiece?>>? = null
    private var previousOccupied: Array<BooleanArray>? = null
    private var previousSignatures: Array<IntArray>? = null
    private var whiteToMove = true
    private var lastFen: String? = null
    private var pendingDigest: Long? = null
    private var pendingCount = 0

    fun reset() {
        board = null
        previousOccupied = null
        previousSignatures = null
        whiteToMove = true
        lastFen = null
        pendingDigest = null
        pendingCount = 0
    }

    fun update(detection: BoardDetectionResult): PositionTrackingResult {
        val occupied = toLogicalOccupancy(detection.occupied, detection.orientation)
        val signatures = toLogicalSignatures(detection.signatures, detection.orientation)
        val digest = digest(occupied, signatures)

        if (pendingDigest == digest) {
            pendingCount++
        } else {
            pendingDigest = digest
            pendingCount = 1
            return PositionTrackingResult.Waiting("Menunggu gambar papan stabil…")
        }
        if (pendingCount < REQUIRED_STABLE_FRAMES) {
            return PositionTrackingResult.Waiting("Menunggu gambar papan stabil…")
        }

        if (board == null) {
            if (!looksLikeInitialPosition(occupied)) {
                return PositionTrackingResult.Waiting(
                    "Mulai pembacaan saat papan masih pada posisi awal permainan."
                )
            }
            board = initialBoard()
            previousOccupied = copyOccupancy(occupied)
            previousSignatures = copySignatures(signatures)
            whiteToMove = true
            val fen = FenConverter.toFen(board!!, true)
            lastFen = fen
            return PositionTrackingResult.Position(fen, changed = true)
        }

        val oldOccupied = previousOccupied
            ?: return PositionTrackingResult.Waiting("Menyinkronkan papan…")
        val oldSignatures = previousSignatures
            ?: return PositionTrackingResult.Waiting("Menyinkronkan papan…")

        if (sameOccupancy(oldOccupied, occupied) &&
            !hasMeaningfulSignatureChange(oldSignatures, signatures)) {
            val fen = FenConverter.toFen(board!!, whiteToMove)
            return PositionTrackingResult.Position(fen, changed = false)
        }

        val move = inferMove(board!!, oldOccupied, occupied, oldSignatures, signatures)
            ?: return PositionTrackingResult.Waiting(
                "Gerakan belum terbaca dengan yakin. Tunggu animasi selesai."
            )

        applyMove(board!!, move.first, move.second)
        whiteToMove = !whiteToMove
        previousOccupied = copyOccupancy(occupied)
        previousSignatures = copySignatures(signatures)

        val fen = FenConverter.toFen(board!!, whiteToMove)
        val changed = fen != lastFen
        lastFen = fen
        return PositionTrackingResult.Position(fen, changed)
    }

    private fun inferMove(
        currentBoard: Array<Array<ChessPiece?>>,
        oldOccupied: Array<BooleanArray>,
        newOccupied: Array<BooleanArray>,
        oldSignatures: Array<IntArray>,
        newSignatures: Array<IntArray>
    ): Pair<Int, Int>? {
        val sources = ArrayList<Int>()
        val currentColor = if (whiteToMove) ChessColor.WHITE else ChessColor.BLACK
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val index = row * 8 + col
                val piece = currentBoard[row][col]
                if (piece != null && piece.color == currentColor &&
                    oldOccupied[row][col] && !newOccupied[row][col]) {
                    sources += index
                }
            }
        }
        if (sources.size != 1) return null

        val source = sources.first()
        val sourceRow = source / 8
        val sourceCol = source % 8
        val piece = currentBoard[sourceRow][sourceCol] ?: return null

        val destinations = ArrayList<Pair<Int, Int>>()
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                if (!newOccupied[row][col]) continue
                val index = row * 8 + col
                if (index == source) continue
                val becameOccupied = !oldOccupied[row][col]
                val visualChange = signatureDistance(
                    oldSignatures[row][col],
                    newSignatures[row][col]
                )
                if (becameOccupied || visualChange >= MIN_SIGNATURE_CHANGE) {
                    destinations += index to visualChange
                }
            }
        }

        return destinations
            .filter { (index, _) -> isPseudoLegal(currentBoard, source, index, piece) }
            .maxByOrNull { (_, change) -> change }
            ?.let { source to it.first }
    }

    private fun isPseudoLegal(
        board: Array<Array<ChessPiece?>>,
        from: Int,
        to: Int,
        piece: ChessPiece
    ): Boolean {
        if (from == to) return false
        val fromRow = from / 8
        val fromCol = from % 8
        val toRow = to / 8
        val toCol = to % 8
        val target = board[toRow][toCol]
        if (target?.color == piece.color) return false

        val dr = toRow - fromRow
        val dc = toCol - fromCol
        return when (piece.type) {
            ChessPieceType.PAWN -> {
                val direction = if (piece.color == ChessColor.WHITE) -1 else 1
                val startRow = if (piece.color == ChessColor.WHITE) 6 else 1
                when {
                    dc == 0 && dr == direction && target == null -> true
                    dc == 0 && dr == direction * 2 && fromRow == startRow &&
                        target == null && board[fromRow + direction][fromCol] == null -> true
                    abs(dc) == 1 && dr == direction -> true
                    else -> false
                }
            }
            ChessPieceType.KNIGHT -> abs(dr) * abs(dc) == 2
            ChessPieceType.BISHOP -> abs(dr) == abs(dc) &&
                pathClear(board, fromRow, fromCol, toRow, toCol)
            ChessPieceType.ROOK -> (dr == 0 || dc == 0) &&
                pathClear(board, fromRow, fromCol, toRow, toCol)
            ChessPieceType.QUEEN -> (dr == 0 || dc == 0 || abs(dr) == abs(dc)) &&
                pathClear(board, fromRow, fromCol, toRow, toCol)
            ChessPieceType.KING -> (abs(dr) <= 1 && abs(dc) <= 1) ||
                (dr == 0 && abs(dc) == 2)
        }
    }

    private fun pathClear(
        board: Array<Array<ChessPiece?>>,
        fromRow: Int,
        fromCol: Int,
        toRow: Int,
        toCol: Int
    ): Boolean {
        val stepRow = (toRow - fromRow).compareTo(0)
        val stepCol = (toCol - fromCol).compareTo(0)
        var row = fromRow + stepRow
        var col = fromCol + stepCol
        while (row != toRow || col != toCol) {
            if (board[row][col] != null) return false
            row += stepRow
            col += stepCol
        }
        return true
    }

    private fun applyMove(board: Array<Array<ChessPiece?>>, from: Int, to: Int) {
        val fromRow = from / 8
        val fromCol = from % 8
        val toRow = to / 8
        val toCol = to % 8
        var piece = board[fromRow][fromCol] ?: return

        if (piece.type == ChessPieceType.PAWN &&
            fromCol != toCol && board[toRow][toCol] == null) {
            board[fromRow][toCol] = null
        }

        if (piece.type == ChessPieceType.KING && abs(toCol - fromCol) == 2) {
            if (toCol > fromCol) {
                board[toRow][5] = board[toRow][7]
                board[toRow][7] = null
            } else {
                board[toRow][3] = board[toRow][0]
                board[toRow][0] = null
            }
        }

        if (piece.type == ChessPieceType.PAWN && (toRow == 0 || toRow == 7)) {
            piece = ChessPiece(ChessPieceType.QUEEN, piece.color)
        }

        board[fromRow][fromCol] = null
        board[toRow][toCol] = piece
    }

    private fun looksLikeInitialPosition(occupied: Array<BooleanArray>): Boolean {
        val outer = occupied[0].count { it } + occupied[1].count { it } +
            occupied[6].count { it } + occupied[7].count { it }
        var middle = 0
        for (row in 2..5) middle += occupied[row].count { it }
        return outer >= 24 && middle <= 4
    }

    private fun initialBoard(): Array<Array<ChessPiece?>> {
        val result = Array(8) { arrayOfNulls<ChessPiece>(8) }
        val backRank = arrayOf(
            ChessPieceType.ROOK,
            ChessPieceType.KNIGHT,
            ChessPieceType.BISHOP,
            ChessPieceType.QUEEN,
            ChessPieceType.KING,
            ChessPieceType.BISHOP,
            ChessPieceType.KNIGHT,
            ChessPieceType.ROOK
        )
        for (col in 0 until 8) {
            result[0][col] = ChessPiece(backRank[col], ChessColor.BLACK)
            result[1][col] = ChessPiece(ChessPieceType.PAWN, ChessColor.BLACK)
            result[6][col] = ChessPiece(ChessPieceType.PAWN, ChessColor.WHITE)
            result[7][col] = ChessPiece(backRank[col], ChessColor.WHITE)
        }
        return result
    }

    private fun toLogicalOccupancy(
        input: Array<BooleanArray>,
        orientation: BoardOrientation
    ): Array<BooleanArray> {
        if (orientation == BoardOrientation.WHITE_BOTTOM) return copyOccupancy(input)
        return Array(8) { row -> BooleanArray(8) { col -> input[7 - row][7 - col] } }
    }

    private fun toLogicalSignatures(
        input: Array<IntArray>,
        orientation: BoardOrientation
    ): Array<IntArray> {
        if (orientation == BoardOrientation.WHITE_BOTTOM) return copySignatures(input)
        return Array(8) { row -> IntArray(8) { col -> input[7 - row][7 - col] } }
    }

    private fun hasMeaningfulSignatureChange(
        old: Array<IntArray>,
        new: Array<IntArray>
    ): Boolean {
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                if (signatureDistance(old[row][col], new[row][col]) >= MIN_SIGNATURE_CHANGE) {
                    return true
                }
            }
        }
        return false
    }

    private fun signatureDistance(first: Int, second: Int): Int =
        Integer.bitCount(first xor second)

    private fun sameOccupancy(
        first: Array<BooleanArray>,
        second: Array<BooleanArray>
    ): Boolean {
        for (row in 0 until 8) {
            if (!first[row].contentEquals(second[row])) return false
        }
        return true
    }

    private fun digest(occupied: Array<BooleanArray>, signatures: Array<IntArray>): Long {
        var value = 1125899906842597L
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                value = value * 31 + if (occupied[row][col]) 1 else 0
                value = value * 31 + signatures[row][col]
            }
        }
        return value
    }

    private fun copyOccupancy(source: Array<BooleanArray>): Array<BooleanArray> =
        Array(source.size) { source[it].copyOf() }

    private fun copySignatures(source: Array<IntArray>): Array<IntArray> =
        Array(source.size) { source[it].copyOf() }

    companion object {
        private const val REQUIRED_STABLE_FRAMES = 2
        private const val MIN_SIGNATURE_CHANGE = 7
    }
}
