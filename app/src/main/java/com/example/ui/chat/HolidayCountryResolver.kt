package com.example.ui.chat

import java.text.Normalizer
import java.util.Locale

private val holidayCountryAliases: Map<String, String> = linkedMapOf(
    "indonesia" to "ID",
    "amerika serikat" to "US",
    "united states of america" to "US",
    "united states" to "US",
    "usa" to "US",
    "u s" to "US",
    "us" to "US",
    "britania raya" to "GB",
    "united kingdom" to "GB",
    "inggris" to "GB",
    "uk" to "GB",
    "u k" to "GB",
    "arab saudi" to "SA",
    "saudi arabia" to "SA",
    "uni emirat arab" to "AE",
    "united arab emirates" to "AE",
    "uea" to "AE",
    "uae" to "AE",
    "korea selatan" to "KR",
    "south korea" to "KR",
    "korea utara" to "KP",
    "north korea" to "KP",
    "tiongkok" to "CN",
    "china" to "CN",
    "jepang" to "JP",
    "jerman" to "DE",
    "belanda" to "NL",
    "prancis" to "FR",
    "spanyol" to "ES",
    "italia" to "IT",
    "turki" to "TR",
    "india" to "IN",
    "australia" to "AU",
    "selandia baru" to "NZ",
    "new zealand" to "NZ",
    "kanada" to "CA",
    "meksiko" to "MX",
    "brasil" to "BR",
    "rusia" to "RU",
    "singapura" to "SG",
    "malaysia" to "MY",
    "thailand" to "TH",
    "vietnam" to "VN",
    "filipina" to "PH"
)

internal fun resolveHolidayCountryCode(messageText: String): String {
    val normalizedText = normalizeCountryText(messageText)
    val isoCodes = Locale.getISOCountries().toSet()

    val explicitCode = Regex(
        """\b(?:negara|country|kode\s+negara)\s+([a-z]{2})\b""",
        RegexOption.IGNORE_CASE
    ).find(normalizedText)?.groupValues?.getOrNull(1)?.uppercase(Locale.ROOT)

    if (explicitCode != null && explicitCode in isoCodes) {
        return explicitCode
    }

    val candidates = mutableListOf<CountryMatch>()

    holidayCountryAliases.forEach { (alias, code) ->
        findWholePhrase(normalizedText, alias)?.let { index ->
            candidates += CountryMatch(index, alias.length, code)
        }
    }

    Locale.getISOCountries().forEach { code ->
        val locale = Locale("", code)
        val names = listOf(
            locale.getDisplayCountry(Locale.forLanguageTag("id-ID")),
            locale.getDisplayCountry(Locale.ENGLISH)
        )

        names.asSequence()
            .map(::normalizeCountryText)
            .filter { it.length >= 4 }
            .distinct()
            .forEach { countryName ->
                findWholePhrase(normalizedText, countryName)?.let { index ->
                    candidates += CountryMatch(index, countryName.length, code)
                }
            }
    }

    return candidates
        .minWithOrNull(compareBy<CountryMatch> { it.index }.thenByDescending { it.matchLength })
        ?.countryCode
        ?: "ID"
}

private data class CountryMatch(
    val index: Int,
    val matchLength: Int,
    val countryCode: String
)

private fun normalizeCountryText(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()

private fun findWholePhrase(text: String, phrase: String): Int? {
    val match = Regex(
        """(?<![a-z0-9])${Regex.escape(phrase)}(?![a-z0-9])"""
    ).find(text)
    return match?.range?.first
}
