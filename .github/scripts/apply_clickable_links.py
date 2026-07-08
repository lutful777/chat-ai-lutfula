from pathlib import Path

path = Path("app/src/main/java/com/example/ui/chat/ChatScreen.kt")
text = path.read_text(encoding="utf-8")

message_marker = """@Composable
fun MessageContent(content: String, isUser: Boolean) {"""

helper = r'''private val chatLinkRegex = Regex(
    """\[([^\]]+)]\(((?:https?://|www\.)[^\s)]+)\)|((?:https?://|www\.)[^\s<>\[\]{}]+)""",
    RegexOption.IGNORE_CASE
)

private fun buildChatLinkText(content: String, linkColor: Color): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var cursor = 0

    chatLinkRegex.findAll(content).forEach { match ->
        if (match.range.first > cursor) {
            builder.append(content.substring(cursor, match.range.first))
        }

        val markdownLabel = match.groups[1]?.value.orEmpty()
        val markdownTarget = match.groups[2]?.value.orEmpty()
        val rawTarget = match.groups[3]?.value.orEmpty()
        val originalTarget = if (markdownTarget.isNotBlank()) markdownTarget else rawTarget
        val cleanedTarget = if (markdownTarget.isNotBlank()) {
            originalTarget
        } else {
            originalTarget.trimEnd('.', ',', ';', ':', '!', '?')
        }
        val trailingText = if (markdownTarget.isBlank()) {
            originalTarget.substring(cleanedTarget.length)
        } else {
            ""
        }
        val normalizedTarget = if (cleanedTarget.startsWith("www.", ignoreCase = true)) {
            "https://$cleanedTarget"
        } else {
            cleanedTarget
        }
        val visibleText = if (markdownLabel.isNotBlank()) markdownLabel else cleanedTarget

        if (normalizedTarget.startsWith("http://", ignoreCase = true) ||
            normalizedTarget.startsWith("https://", ignoreCase = true)
        ) {
            builder.pushStringAnnotation(tag = "URL", annotation = normalizedTarget)
            builder.pushStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = linkColor,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )
            )
            builder.append(visibleText)
            builder.pop()
            builder.pop()
        } else {
            builder.append(visibleText)
        }

        builder.append(trailingText)
        cursor = match.range.last + 1
    }

    if (cursor < content.length) {
        builder.append(content.substring(cursor))
    }
    return builder.toAnnotatedString()
}

@Suppress("DEPRECATION")
@Composable
private fun ClickableLinkText(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    color: Color = Color.White
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedText = remember(content, linkColor) {
        buildChatLinkText(content, linkColor)
    }

    androidx.compose.foundation.text.ClickableText(
        text = annotatedText,
        modifier = modifier,
        style = style.copy(color = color),
        onClick = { offset ->
            annotatedText.getStringAnnotations(
                tag = "URL",
                start = offset,
                end = offset
            ).firstOrNull()?.let { annotation ->
                val target = annotation.item
                if (target.startsWith("http://", ignoreCase = true) ||
                    target.startsWith("https://", ignoreCase = true)
                ) {
                    runCatching { uriHandler.openUri(target) }
                }
            }
        }
    )
}

@Composable
fun MessageContent(content: String, isUser: Boolean) {'''

if "private fun ClickableLinkText(" not in text:
    if message_marker not in text:
        raise SystemExit("MessageContent marker not found")
    text = text.replace(message_marker, helper, 1)

old_plain = '''    if (isUser || !content.contains("```")) {
        Text(
            text = content,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        return
    }'''
new_plain = '''    if (isUser || !content.contains("```")) {
        ClickableLinkText(content = content)
        return
    }'''
if old_plain in text:
    text = text.replace(old_plain, new_plain, 1)
elif "ClickableLinkText(content = content)" not in text:
    raise SystemExit("Plain message Text block not found")

old_segment = '''                    Text(
                        text = block.trim('\\n'),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )'''
new_segment = '''                    ClickableLinkText(content = block.trim('\\n'))'''
if old_segment in text:
    text = text.replace(old_segment, new_segment, 1)
elif "ClickableLinkText(content = block.trim('\\n'))" not in text:
    raise SystemExit("Non-code message Text block not found")

old_prompt = '''            Text(
                text = promptText,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )'''
new_prompt = '''            ClickableLinkText(
                content = promptText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )'''
if old_prompt in text:
    text = text.replace(old_prompt, new_prompt, 1)
elif "content = promptText,\n                style = MaterialTheme.typography.bodySmall" not in text:
    raise SystemExit("Prompt Text block not found")

path.write_text(text, encoding="utf-8")
