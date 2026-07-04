package com.example.ui.chat

import java.util.Locale

private val webUrlRegex = Regex(
    """https?://[^\s<>\"']+""",
    RegexOption.IGNORE_CASE
)

internal fun extractWebUrls(text: String): List<String> = webUrlRegex
    .findAll(text)
    .map { match -> match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}') }
    .filter { it.isNotBlank() }
    .distinct()
    .toList()

internal fun resolveReferencedWebUrls(
    currentMessage: String,
    previousMessages: List<String>
): List<String> {
    val currentUrls = extractWebUrls(currentMessage)
    if (currentUrls.isNotEmpty()) return currentUrls

    val refersToPreviousPage = Regex(
        """\b(website|situs|web|halaman|link|url)\s+(itu|tadi|tersebut|sebelumnya|di\s+atas)\b|\b(di|dari)\s+(website|situs|web|halaman|link|url)\s+(itu|tadi|tersebut|sebelumnya)\b""",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(currentMessage)

    if (!refersToPreviousPage) return emptyList()

    return previousMessages
        .asReversed()
        .asSequence()
        .flatMap { extractWebUrls(it).asSequence() }
        .take(1)
        .toList()
}

internal fun shouldUseAutomaticBrowser(messageText: String): Boolean {
    val text = messageText.trim().lowercase(Locale.ROOT)
    if (text.isBlank()) return false

    val explicitWebRequest = Regex(
        """\b(cari|carikan|cek|periksa|telusuri|buka|lihat|temukan)\b.{0,45}\b(internet|online|web|website|situs|browser)\b|\b(internet|online|web|website|situs|browser)\b.{0,45}\b(cari|cek|periksa|telusuri|buka|lihat|temukan)\b"""
    ).containsMatchIn(text)

    val freshnessRequest = Regex(
        """\b(terbaru|terkini|sekarang|saat\s+ini|hari\s+ini|baru\s+saja|realtime|real\s*time|update|pembaruan|minggu\s+ini|bulan\s+ini|tahun\s+ini|masih\s+tersedia|status\s+terkini|jadwal|skor|hasil\s+pertandingan|cuaca|berita|news|rilis\s+terbaru)\b"""
    ).containsMatchIn(text)

    val websiteQuestion = Regex(
        """\b(website|situs|web|halaman|portal|dashboard|platform|provider)\b"""
    ).containsMatchIn(text) && Regex(
        """\b(apa|siapa|berapa|mana|apakah|daftar|model|fitur|harga|pricing|paket|produk|layanan|tersedia|ada)\b"""
    ).containsMatchIn(text)

    val onlineCatalogQuestion = Regex(
        """\b(model\s+ai|daftar\s+model|model\s+apa\s+saja|produk\s+apa\s+saja|fitur\s+apa\s+saja|pricing|paket|layanan)\b"""
    ).containsMatchIn(text) && Regex(
        """\b(tersedia|ada\s+di|disediakan|ditawarkan|platform|provider|website|situs)\b"""
    ).containsMatchIn(text)

    val changingRoleOrStatus = Regex(
        """\b(siapa|apakah|berapa|kapan)\b.{0,55}\b(presiden|perdana\s+menteri|ceo|ketua|gubernur|menteri|harga|kurs|jadwal|status|versi)\b"""
    ).containsMatchIn(text)

    val localOrCreativeTask = Regex(
        """\b(buatkan|tuliskan|karang|cerita|puisi|terjemahkan|parafrase|perbaiki\s+kalimat|hitung)\b"""
    ).containsMatchIn(text)

    if (localOrCreativeTask && !explicitWebRequest && !freshnessRequest && !websiteQuestion) {
        return false
    }

    return explicitWebRequest || freshnessRequest || websiteQuestion ||
        onlineCatalogQuestion || changingRoleOrStatus
}

internal fun resolveAutomaticBrowserMode(messageText: String): String {
    val text = messageText.lowercase(Locale.ROOT)
    return if (
        text.trimStart().startsWith("#berita") ||
        Regex("""\b(berita|news|baru\s+saja|hari\s+ini|terbaru|terkini)\b""").containsMatchIn(text)
    ) {
        "berita"
    } else {
        "cari"
    }
}
