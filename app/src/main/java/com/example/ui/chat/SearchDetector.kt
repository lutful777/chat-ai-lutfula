package com.example.ui.chat

object SearchDetector {
    private val freshnessKeywords = listOf(
        "berita", "kabar terbaru", "berita terbaru", "hari ini", "baru-baru ini",
        "sedang viral", "update terbaru", "apa yang terjadi", "kabar terkini"
    )

    private val eventKeywords = listOf(
        "meninggal", "wafat", "ditangkap", "kecelakaan", "gempa", "tsunami",
        "meletus", "menangis", "menang", "kalah", "perang", "diserang",
        "terluka", "dipecat", "mundur", "hilang", "ditemukan"
    )

    private val genericSubjects = setOf(
        "orang", "manusia", "seseorang", "dia", "mereka", "anak", "bayi",
        "hewan", "kucing", "anjing", "kita", "saya", "aku", "kamu"
    )

    private val questionStarters = setOf(
        "apa", "apakah", "kenapa", "mengapa", "bagaimana", "siapa", "kapan",
        "dimana", "di", "cara", "arti", "penyebab"
    )

    fun detectSearchModeAndQuery(messageText: String): Pair<String?, String> {
        val text = messageText.trim()
        val textLower = text.lowercase()

        if (textLower.startsWith("#berita")) return Pair(text.drop(7).trim(), "berita")
        if (textLower.startsWith("#browser")) return Pair(text.drop(8).trim(), "cari")
        if (textLower.startsWith("#cari")) return Pair(text.drop(5).trim(), "cari")

        if (text.length < 6) return Pair(null, "cari")

        val words = textLower
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val hasFreshnessSignal = freshnessKeywords.any { textLower.contains(it) }
        val hasEventSignal = eventKeywords.any { textLower.contains(it) }
        val isQuestion = text.contains("?") || words.firstOrNull() in questionStarters

        val capitalizedNames = text
            .split(Regex("\\s+"))
            .count { token -> token.firstOrNull()?.isUpperCase() == true && token.length > 1 }
        val hasCapitalizedName = capitalizedNames >= 1

        val leadingSubjectWords = words
            .dropWhile { it in questionStarters }
            .take(2)
        val hasSpecificLeadingSubject = leadingSubjectWords.isNotEmpty() &&
            leadingSubjectWords.none { it in genericSubjects } &&
            leadingSubjectWords.none { it in questionStarters }

        val looksLikeGeneralExplanation = listOf(
            "kenapa manusia", "mengapa manusia", "kenapa orang", "mengapa orang",
            "apa arti", "cara mengatasi", "penyebab seseorang", "contoh orang"
        ).any { textLower.startsWith(it) }

        val isAutomaticNews = when {
            looksLikeGeneralExplanation -> false
            hasFreshnessSignal -> true
            hasEventSignal && isQuestion && (hasCapitalizedName || hasSpecificLeadingSubject) -> true
            else -> false
        }

        return if (isAutomaticNews) Pair(text, "berita") else Pair(null, "cari")
    }
}
