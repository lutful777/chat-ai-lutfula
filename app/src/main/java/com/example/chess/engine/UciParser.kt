package com.example.chess.engine

import java.util.Locale

object UciParser {
    fun parseBestMove(line: String): Pair<String, String?>? {
        if (!line.startsWith("bestmove")) return null
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null

        val bestMove = parts[1]
        if (bestMove == "(none)" || bestMove == "0000") return null
        val ponder = if (parts.size >= 4 && parts[2] == "ponder") parts[3] else null
        return bestMove to ponder
    }

    fun parseInfo(line: String): UciInfo? {
        if (!line.startsWith("info ")) return null

        var depth = 0
        var scoreCp: Int? = null
        var scoreMate: Int? = null
        var principalVariation = ""
        var timeMs = 0L

        val parts = line.trim().split(Regex("\\s+"))
        var index = 1
        while (index < parts.size) {
            when (parts[index]) {
                "depth" -> if (index + 1 < parts.size) depth = parts[++index].toIntOrNull() ?: 0
                "time" -> if (index + 1 < parts.size) timeMs = parts[++index].toLongOrNull() ?: 0L
                "score" -> {
                    if (index + 2 < parts.size) {
                        val type = parts[index + 1]
                        val value = parts[index + 2].toIntOrNull() ?: 0
                        if (type == "cp") scoreCp = value
                        if (type == "mate") scoreMate = value
                        index += 2
                    }
                }
                "pv" -> {
                    if (index + 1 < parts.size) {
                        principalVariation = parts.subList(index + 1, parts.size).joinToString(" ")
                    }
                    break
                }
            }
            index++
        }

        val evaluation = when {
            scoreMate != null -> "M$scoreMate"
            scoreCp != null -> String.format(Locale.US, "%.2f", scoreCp / 100.0)
            else -> ""
        }

        return UciInfo(depth, evaluation, timeMs, principalVariation)
    }
}

data class UciInfo(
    val depth: Int,
    val evaluation: String,
    val timeMs: Long,
    val principalVariation: String
)
