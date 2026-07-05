package com.example.chess.engine

import com.example.chess.domain.ChessAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Lightweight, fully local chess engine used when no native UCI binary is bundled.
 * It generates legal moves, rejects moves that leave the king in check, and uses
 * a small alpha-beta search. Search depth is capped to keep mobile CPU usage safe.
 */
class SimpleChessEngine : ChessEngine {
    @Volatile
    private var stopRequested = false

    override suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult = withContext(Dispatchers.Default) {
        stopRequested = false
        val position = Position.fromFen(fen)
            ?: return@withContext ChessAnalysisResult("-", null, "FEN tidak valid", 0)

        val actualDepth = depth.coerceIn(1, MAX_DEPTH)
        val moves = legalMoves(position)
        if (moves.isEmpty()) {
            val message = if (isKingInCheck(position, position.whiteToMove)) "Skakmat" else "Remis"
            return@withContext ChessAnalysisResult("-", null, message, actualDepth)
        }

        var bestMove: Move? = null
        var bestScore = -INF
        var alpha = -INF
        val beta = INF

        for (move in orderedMoves(position, moves)) {
            coroutineContext.ensureActive()
            if (stopRequested) break
            val next = position.play(move)
            val score = -negamax(next, actualDepth - 1, -beta, -alpha, 1)
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
            if (score > alpha) alpha = score
        }

        val selected = bestMove ?: moves.first()
        val resulting = position.play(selected)
        val reply = legalMoves(resulting).maxByOrNull { moveOrderingScore(resulting, it) }
        val whiteScore = if (position.whiteToMove) bestScore else -bestScore
        val evaluation = when {
            abs(whiteScore) >= MATE_SCORE - 100 -> if (whiteScore > 0) "M+" else "M-"
            else -> "%+.2f".format(whiteScore / 100.0)
        }

        ChessAnalysisResult(
            bestMove = selected.toUci(),
            ponderMove = reply?.toUci(),
            evaluation = evaluation,
            depth = actualDepth
        )
    }

    override fun stopAnalysis() {
        stopRequested = true
    }

    override fun close() {
        stopRequested = true
    }

    private suspend fun negamax(
        position: Position,
        depth: Int,
        alphaInput: Int,
        beta: Int,
        ply: Int
    ): Int {
        coroutineContext.ensureActive()
        if (stopRequested) return 0
        if (depth <= 0) {
            val score = evaluateWhite(position)
            return if (position.whiteToMove) score else -score
        }

        val moves = legalMoves(position)
        if (moves.isEmpty()) {
            return if (isKingInCheck(position, position.whiteToMove)) -MATE_SCORE + ply else 0
        }

        var alpha = alphaInput
        var best = -INF
        for (move in orderedMoves(position, moves)) {
            if (stopRequested) break
            val score = -negamax(position.play(move), depth - 1, -beta, -alpha, ply + 1)
            if (score > best) best = score
            if (score > alpha) alpha = score
            if (alpha >= beta) break
        }
        return best
    }

    private fun orderedMoves(position: Position, moves: List<Move>): List<Move> =
        moves.sortedByDescending { moveOrderingScore(position, it) }

    private fun moveOrderingScore(position: Position, move: Move): Int {
        val captured = position.board[move.to]
        val moving = position.board[move.from]
        return pieceValue(captured) * 10 - pieceValue(moving) + if (move.promotion != null) 800 else 0
    }

    private fun evaluateWhite(position: Position): Int {
        var score = 0
        for (index in position.board.indices) {
            val piece = position.board[index]
            if (piece == EMPTY) continue
            val row = index / 8
            val col = index % 8
            val centerBonus = 4 - (abs(3 - row) + abs(3 - col))
            val value = pieceValue(piece) + centerBonus.coerceAtLeast(0) * 2
            score += if (piece.isUpperCase()) value else -value
        }
        return score
    }

    private fun pieceValue(piece: Char): Int = when (piece.lowercaseChar()) {
        'p' -> 100
        'n' -> 320
        'b' -> 330
        'r' -> 500
        'q' -> 900
        'k' -> 20_000
        else -> 0
    }

    private fun legalMoves(position: Position): List<Move> {
        val pseudo = pseudoLegalMoves(position)
        return pseudo.filter { move ->
            val next = position.play(move)
            !isKingInCheck(next, position.whiteToMove)
        }
    }

    private fun pseudoLegalMoves(position: Position): List<Move> {
        val result = ArrayList<Move>(48)
        for (from in position.board.indices) {
            val piece = position.board[from]
            if (piece == EMPTY || piece.isUpperCase() != position.whiteToMove) continue
            val row = from / 8
            val col = from % 8
            when (piece.lowercaseChar()) {
                'p' -> addPawnMoves(position, result, from, row, col, piece.isUpperCase())
                'n' -> addJumpMoves(position, result, from, row, col, KNIGHT_DELTAS)
                'b' -> addSlidingMoves(position, result, from, row, col, BISHOP_DIRECTIONS)
                'r' -> addSlidingMoves(position, result, from, row, col, ROOK_DIRECTIONS)
                'q' -> addSlidingMoves(position, result, from, row, col, QUEEN_DIRECTIONS)
                'k' -> addJumpMoves(position, result, from, row, col, KING_DELTAS)
            }
        }
        return result
    }

    private fun addPawnMoves(
        position: Position,
        result: MutableList<Move>,
        from: Int,
        row: Int,
        col: Int,
        white: Boolean
    ) {
        val direction = if (white) -1 else 1
        val startRow = if (white) 6 else 1
        val promotionRow = if (white) 0 else 7
        val oneRow = row + direction
        if (oneRow in 0..7) {
            val one = oneRow * 8 + col
            if (position.board[one] == EMPTY) {
                result += Move(from, one, if (oneRow == promotionRow) 'q' else null)
                val twoRow = row + direction * 2
                val two = twoRow * 8 + col
                if (row == startRow && position.board[two] == EMPTY) result += Move(from, two)
            }
            for (dc in intArrayOf(-1, 1)) {
                val captureCol = col + dc
                if (captureCol !in 0..7) continue
                val to = oneRow * 8 + captureCol
                val target = position.board[to]
                if (target != EMPTY && target.isUpperCase() != white) {
                    result += Move(from, to, if (oneRow == promotionRow) 'q' else null)
                }
            }
        }
    }

    private fun addJumpMoves(
        position: Position,
        result: MutableList<Move>,
        from: Int,
        row: Int,
        col: Int,
        deltas: Array<IntArray>
    ) {
        val white = position.board[from].isUpperCase()
        for (delta in deltas) {
            val r = row + delta[0]
            val c = col + delta[1]
            if (r !in 0..7 || c !in 0..7) continue
            val to = r * 8 + c
            val target = position.board[to]
            if (target == EMPTY || target.isUpperCase() != white) result += Move(from, to)
        }
    }

    private fun addSlidingMoves(
        position: Position,
        result: MutableList<Move>,
        from: Int,
        row: Int,
        col: Int,
        directions: Array<IntArray>
    ) {
        val white = position.board[from].isUpperCase()
        for (direction in directions) {
            var r = row + direction[0]
            var c = col + direction[1]
            while (r in 0..7 && c in 0..7) {
                val to = r * 8 + c
                val target = position.board[to]
                if (target == EMPTY) {
                    result += Move(from, to)
                } else {
                    if (target.isUpperCase() != white) result += Move(from, to)
                    break
                }
                r += direction[0]
                c += direction[1]
            }
        }
    }

    private fun isKingInCheck(position: Position, whiteKing: Boolean): Boolean {
        val king = if (whiteKing) 'K' else 'k'
        val square = position.board.indexOf(king)
        if (square < 0) return true
        return isSquareAttacked(position, square, byWhite = !whiteKing)
    }

    private fun isSquareAttacked(position: Position, square: Int, byWhite: Boolean): Boolean {
        val row = square / 8
        val col = square % 8

        val pawnRow = row + if (byWhite) 1 else -1
        if (pawnRow in 0..7) {
            for (dc in intArrayOf(-1, 1)) {
                val c = col + dc
                if (c in 0..7) {
                    val expected = if (byWhite) 'P' else 'p'
                    if (position.board[pawnRow * 8 + c] == expected) return true
                }
            }
        }

        val knight = if (byWhite) 'N' else 'n'
        for (delta in KNIGHT_DELTAS) {
            val r = row + delta[0]
            val c = col + delta[1]
            if (r in 0..7 && c in 0..7 && position.board[r * 8 + c] == knight) return true
        }

        val king = if (byWhite) 'K' else 'k'
        for (delta in KING_DELTAS) {
            val r = row + delta[0]
            val c = col + delta[1]
            if (r in 0..7 && c in 0..7 && position.board[r * 8 + c] == king) return true
        }

        if (attackedBySlider(position, row, col, byWhite, BISHOP_DIRECTIONS, 'b', 'q')) return true
        if (attackedBySlider(position, row, col, byWhite, ROOK_DIRECTIONS, 'r', 'q')) return true
        return false
    }

    private fun attackedBySlider(
        position: Position,
        row: Int,
        col: Int,
        byWhite: Boolean,
        directions: Array<IntArray>,
        firstType: Char,
        secondType: Char
    ): Boolean {
        for (direction in directions) {
            var r = row + direction[0]
            var c = col + direction[1]
            while (r in 0..7 && c in 0..7) {
                val piece = position.board[r * 8 + c]
                if (piece != EMPTY) {
                    if (piece.isUpperCase() == byWhite) {
                        val type = piece.lowercaseChar()
                        if (type == firstType || type == secondType) return true
                    }
                    break
                }
                r += direction[0]
                c += direction[1]
            }
        }
        return false
    }

    private data class Move(val from: Int, val to: Int, val promotion: Char? = null) {
        fun toUci(): String = squareName(from) + squareName(to) + (promotion?.toString() ?: "")
    }

    private data class Position(val board: CharArray, val whiteToMove: Boolean) {
        fun play(move: Move): Position {
            val next = board.copyOf()
            var piece = next[move.from]
            next[move.from] = EMPTY
            if (move.promotion != null) {
                piece = if (piece.isUpperCase()) move.promotion.uppercaseChar() else move.promotion
            }
            next[move.to] = piece
            return Position(next, !whiteToMove)
        }

        companion object {
            fun fromFen(fen: String): Position? {
                val fields = fen.trim().split(Regex("\\s+"))
                if (fields.size < 2) return null
                val rows = fields[0].split('/')
                if (rows.size != 8) return null
                val board = CharArray(64) { EMPTY }
                for (row in 0 until 8) {
                    var col = 0
                    for (token in rows[row]) {
                        if (token.isDigit()) {
                            col += token.digitToInt()
                        } else {
                            if (col !in 0..7 || token.lowercaseChar() !in "pnbrqk") return null
                            board[row * 8 + col] = token
                            col++
                        }
                    }
                    if (col != 8) return null
                }
                return Position(board, fields[1] == "w")
            }
        }
    }

    companion object {
        private const val EMPTY = '.'
        private const val INF = 1_000_000
        private const val MATE_SCORE = 100_000
        private const val MAX_DEPTH = 3

        private val KNIGHT_DELTAS = arrayOf(
            intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, -2), intArrayOf(-1, 2),
            intArrayOf(1, -2), intArrayOf(1, 2), intArrayOf(2, -1), intArrayOf(2, 1)
        )
        private val KING_DELTAS = arrayOf(
            intArrayOf(-1, -1), intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, -1),
            intArrayOf(0, 1), intArrayOf(1, -1), intArrayOf(1, 0), intArrayOf(1, 1)
        )
        private val BISHOP_DIRECTIONS = arrayOf(
            intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1)
        )
        private val ROOK_DIRECTIONS = arrayOf(
            intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1)
        )
        private val QUEEN_DIRECTIONS = BISHOP_DIRECTIONS + ROOK_DIRECTIONS

        private fun squareName(index: Int): String {
            val file = ('a'.code + index % 8).toChar()
            val rank = 8 - index / 8
            return "$file$rank"
        }
    }
}
