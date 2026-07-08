package com.example.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import com.example.ui.theme.PrimaryNeon
import java.net.URI

internal data class MessageLinkSegment(
    val text: String,
    val url: String? = null
)

private const val URL_ANNOTATION_TAG = "web_url"

private val messageLinkRegex = Regex(
    pattern = """\[([^\]\n]+)]\(((?:https?://|www\.)[^\s)]+)\)|\b(?:https?://|www\.)[^\s<>()\[\]{}]+""",
    option = RegexOption.IGNORE_CASE
)

private val trailingUrlPunctuation = setOf('.', ',', ';', ':', '!', '?', ']', '}')

internal fun normalizeSafeWebUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return null

    val normalized = if (trimmed.startsWith("www.", ignoreCase = true)) {
        "https://$trimmed"
    } else {
        trimmed
    }

    return try {
        val uri = URI(normalized)
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank()) return null
        normalized
    } catch (_: Exception) {
        null
    }
}

private fun splitTrailingPunctuation(rawUrl: String): Pair<String, String> {
    var endIndex = rawUrl.length
    while (endIndex > 0 && rawUrl[endIndex - 1] in trailingUrlPunctuation) {
        endIndex--
    }
    return rawUrl.substring(0, endIndex) to rawUrl.substring(endIndex)
}

internal fun parseMessageLinks(text: String): List<MessageLinkSegment> {
    if (text.isEmpty()) return listOf(MessageLinkSegment(""))

    val segments = mutableListOf<MessageLinkSegment>()
    var cursor = 0

    messageLinkRegex.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            segments += MessageLinkSegment(text.substring(cursor, match.range.first))
        }

        val markdownLabel = match.groups[1]?.value
        val markdownUrl = match.groups[2]?.value

        if (markdownLabel != null && markdownUrl != null) {
            val safeUrl = normalizeSafeWebUrl(markdownUrl)
            if (safeUrl != null) {
                segments += MessageLinkSegment(markdownLabel, safeUrl)
            } else {
                segments += MessageLinkSegment(match.value)
            }
        } else {
            val (urlPart, punctuation) = splitTrailingPunctuation(match.value)
            val safeUrl = normalizeSafeWebUrl(urlPart)
            if (safeUrl != null) {
                segments += MessageLinkSegment(urlPart, safeUrl)
                if (punctuation.isNotEmpty()) {
                    segments += MessageLinkSegment(punctuation)
                }
            } else {
                segments += MessageLinkSegment(match.value)
            }
        }

        cursor = match.range.last + 1
    }

    if (cursor < text.length) {
        segments += MessageLinkSegment(text.substring(cursor))
    }

    return if (segments.isEmpty()) listOf(MessageLinkSegment(text)) else segments
}

private fun openWebLink(context: Context, rawUrl: String) {
    val safeUrl = normalizeSafeWebUrl(rawUrl) ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Tidak ada aplikasi yang dapat membuka link ini.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Overload khusus untuk isi pesan chat. Signature ini sengaja sama dengan pemanggilan
 * teks pesan di ChatScreen, sehingga link dari pengguna maupun AI menjadi aktif tanpa
 * mengubah komponen teks lain di aplikasi.
 */
@Suppress("DEPRECATION")
@Composable
fun Text(
    text: String,
    color: Color,
    style: TextStyle,
    fontSize: TextUnit,
    lineHeight: TextUnit
) {
    val context = LocalContext.current
    val linkColor = PrimaryNeon
    val segments = remember(text) { parseMessageLinks(text) }
    val annotatedText = remember(text, linkColor) {
        buildAnnotatedString {
            segments.forEach { segment ->
                val url = segment.url
                if (url == null) {
                    append(segment.text)
                } else {
                    pushStringAnnotation(tag = URL_ANNOTATION_TAG, annotation = url)
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(segment.text)
                    }
                    pop()
                }
            }
        }
    }

    ClickableText(
        text = annotatedText,
        style = style.copy(
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight
        ),
        onClick = { offset ->
            annotatedText
                .getStringAnnotations(URL_ANNOTATION_TAG, offset, offset)
                .firstOrNull()
                ?.let { annotation -> openWebLink(context, annotation.item) }
        }
    )
}
