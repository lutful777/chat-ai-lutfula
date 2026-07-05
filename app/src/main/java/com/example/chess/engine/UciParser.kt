package com.example.chess.engine

object UciParser {
    fun parseBestMove(line: String): Pair<String, String?>? {
        if (!line.startsWith("bestmove")) return null
        val parts = line.split(" ")
        if (parts.size >= 2) {
            val bestMove = parts[1]
            if (bestMove == "(none)" || bestMove == "0000") return null
            val ponder = if (parts.size >= 4 && parts[2] == "ponder") parts[3] else null
            return bestMove to ponder
        }
        return null
    }

    fun parseInfo(line: String): UciInfo? {
        if (!line.startsWith("info ")) return null
        
        var depth = 0
        var scoreCp: Int? = null
        var scoreMate: Int? = null
        var pv = ""
        var timeMs = 0L

        val parts = line.split(" ")
        var i = 1
        while (i < parts.size) {
            when (parts[i]) {
                "depth" -> if (i + 1 < parts.size) depth = parts[++i].toIntOrNull() ?: 0
                "time" -> if (i + 1 < parts.size) timeMs = parts[++i].toLongOrNull() ?: 0L
                "score" -> {
                    if (i + 2 < parts.size) {
                        val type = parts[i + 1]
                        val value = parts[i + 2].toIntOrNull() ?: 0
                        if (type == "cp") scoreCp = value
                        else if (type == "mate") scoreMate = value
                        i += 2
                    }
                }
                "pv" -> {
                    if (i + 1 < parts.size) {
                        pv = parts.subList(i + 1, parts.size).joinToString(" ")
                    }
                    break // PV is usually the last part
                }
            }
            i++
        }
        
        val eval = when {
            scoreMate != null -> "M$scoreMate"
            scoreCp != null -> String.format("%.2f", scoreCp / 100.0)
            else -> ""
        }

        return UciInfo(depth, eval, timeMs, pv)
    }
}

data class UciInfo(
    val depth: Int,
    val evaluation: String,
    val timeMs: Long,
    val principalVariation: String
)
