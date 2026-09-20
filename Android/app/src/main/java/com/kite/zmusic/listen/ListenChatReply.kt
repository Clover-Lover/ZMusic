package com.kite.zmusic.listen

/**
 * 一起听聊天「回复」仅走 UI + 文本嵌入（社区接口暂无 reply_to）。
 * 本客户端能解析并画 Telegram 风引用条；其它端仍可读正文。
 */
data class ListenChatReplyQuote(
    val msgId: Long,
    val uid: String,
    val nickname: String,
    val snippet: String,
)

data class ListenChatParsedText(
    val reply: ListenChatReplyQuote?,
    val body: String,
)

/** 行首标记：零宽 + 可打印前缀，降低被当正文展示的概率。 */
private const val REPLY_LINE_PREFIX = "\u200B\u200BZM1|"

fun listenChatQuoteFromMsg(msg: ListenChatMsg): ListenChatReplyQuote {
    val parsed = parseListenChatText(msg.text)
    val body = parsed.body.ifBlank { msg.text }
    return ListenChatReplyQuote(
        msgId = msg.id,
        uid = msg.uid,
        nickname = msg.nickname.ifBlank { msg.uid },
        snippet = body.lineSequence().firstOrNull().orEmpty().trim().take(120),
    )
}

fun encodeListenChatText(reply: ListenChatReplyQuote?, body: String): String {
    val text = body.trimEnd()
    if (reply == null) return text
    val nick = sanitizeReplyField(reply.nickname).ifBlank { reply.uid }
    val snip = sanitizeReplyField(reply.snippet).ifBlank { "…" }
    val header = buildString {
        append(REPLY_LINE_PREFIX)
        append(reply.msgId)
        append('|')
        append(sanitizeReplyField(reply.uid))
        append('|')
        append(nick)
        append('|')
        append(snip)
    }
    return if (text.isEmpty()) header else "$header\n$text"
}

fun parseListenChatText(raw: String): ListenChatParsedText {
    if (raw.isEmpty()) return ListenChatParsedText(null, raw)
    val firstNl = raw.indexOf('\n')
    val firstLine = if (firstNl >= 0) raw.substring(0, firstNl) else raw
    if (!firstLine.startsWith(REPLY_LINE_PREFIX)) {
        return ListenChatParsedText(null, raw)
    }
    val payload = firstLine.removePrefix(REPLY_LINE_PREFIX)
    val parts = payload.split('|', limit = 4)
    if (parts.size < 4) return ListenChatParsedText(null, raw)
    val id = parts[0].toLongOrNull() ?: return ListenChatParsedText(null, raw)
    val body = if (firstNl >= 0) raw.substring(firstNl + 1) else ""
    return ListenChatParsedText(
        reply = ListenChatReplyQuote(
            msgId = id,
            uid = parts[1],
            nickname = parts[2].ifBlank { parts[1] },
            snippet = parts[3],
        ),
        body = body,
    )
}

/** 复制 / 翻译 / 气泡截断用可见正文。 */
fun listenChatVisibleBody(raw: String): String =
    parseListenChatText(raw).body.ifBlank { raw }

private fun sanitizeReplyField(value: String): String =
    value.replace('\n', ' ')
        .replace('\r', ' ')
        .replace('|', '/')
        .trim()
        .take(120)
