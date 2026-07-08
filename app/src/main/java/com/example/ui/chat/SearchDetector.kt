package com.example.ui.chat

object SearchDetector {
    fun detectSearchModeAndQuery(messageText: String): Pair<String?, String> {
        val text = messageText.trim()
        val textLower = text.lowercase()
        
        if (textLower.startsWith("#berita")) return Pair(text.substring(7).trim(), "berita")
        if (textLower.startsWith("#browser")) return Pair(text.substring(8).trim(), "cari")
        if (textLower.startsWith("#cari")) return Pair(text.substring(5).trim(), "cari")
        
        val strongNewsKeywords = listOf(
            "berita", "kabar terbaru", "sedang viral", "apa yang terjadi", 
            "kecelakaan", "gempa", "tsunami", "meletus"
        )
        val weakNewsKeywords = listOf(
            "terbaru", "hari ini", "baru-baru ini", "update", "kejadian", 
            "menangis", "meninggal", "ditangkap", "menang", "kalah", "perang"
        )
        
        val hasStrong = strongNewsKeywords.any { textLower.contains(it) }
        val hasWeak = weakNewsKeywords.any { textLower.contains(it) }
        
        val isQuestion = text.contains("?") || textLower.contains("apa") || textLower.contains("siapa") || textLower.contains("kenapa") || textLower.contains("bagaimana")
        val hasNamePattern = text.length > 5 && text.any { it.isUpperCase() }
        
        if (hasStrong || (hasWeak && (isQuestion || hasNamePattern))) {
            if (text.length > 5) {
                return Pair(text, "berita")
            }
        }
        
        return Pair(null, "cari")
    }
}
