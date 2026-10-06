package com.kite.zmusic.plugin

/**
 * 插件写进 `innerHTML` 的标记。
 * 只建元素、属性和文本，不执行 `<script>`，也不跑标签上的事件属性。
 */
internal object BetterNcmHtml {
    data class Piece(
        val tag: String,
        val attrs: Map<String, String>,
        val text: String,
        val children: List<Piece>,
    )

    fun parse(html: String): List<Piece> {
        val root = Frame("div")
        val stack = ArrayDeque<Frame>()
        stack.add(root)
        var i = 0
        while (i < html.length) {
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                i = if (end < 0) html.length else end + 3
                continue
            }
            if (html[i] != '<') {
                val next = html.indexOf('<', i).let { if (it < 0) html.length else it }
                stack.last().text.append(decode(html.substring(i, next)))
                i = next
                continue
            }
            if (i + 1 < html.length && html[i + 1] == '/') {
                val end = html.indexOf('>', i)
                if (end < 0) break
                val name = html.substring(i + 2, end).trim().substringBefore(' ').lowercase()
                i = end + 1
                closeUntil(stack, name)
                continue
            }
            val end = html.indexOf('>', i)
            if (end < 0) break
            val raw = html.substring(i + 1, end).trim()
            i = end + 1
            if (raw.isEmpty() || raw.startsWith("!") || raw.startsWith("?")) continue
            val selfClose = raw.endsWith("/")
            val body = if (selfClose) raw.dropLast(1).trim() else raw
            val tag = body.substringBefore(' ').substringBefore('\t').substringBefore('\n').lowercase()
            if (tag.isEmpty() || !tag[0].isLetter()) continue
            val attrs = parseAttrs(if (body.length > tag.length) body.substring(tag.length) else "")
            val frame = Frame(tag, attrs)
            stack.last().children.add(frame)
            if (selfClose || tag in VOID_TAGS) continue
            stack.addLast(frame)
            if (tag == "style" || tag == "script") {
                val close = "</$tag>"
                val closeAt = html.indexOf(close, i, ignoreCase = true)
                if (closeAt < 0) {
                    frame.text.append(html.substring(i))
                    i = html.length
                } else {
                    frame.text.append(html.substring(i, closeAt))
                    i = closeAt + close.length
                }
                if (stack.last() === frame) stack.removeLast()
            }
        }
        return root.children.map { it.toPiece() }
    }

    private fun closeUntil(stack: ArrayDeque<Frame>, name: String) {
        if (name.isEmpty() || stack.size <= 1) return
        for (index in stack.lastIndex downTo 1) {
            if (stack[index].tag == name) {
                while (stack.lastIndex >= index) stack.removeLast()
                return
            }
        }
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < raw.length) {
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length || raw[i] == '/') break
            val start = i
            while (i < raw.length && raw[i] != '=' && !raw[i].isWhitespace() && raw[i] != '/') i++
            val name = raw.substring(start, i).lowercase()
            if (name.isEmpty()) {
                i++
                continue
            }
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length || raw[i] != '=') {
                out[name] = ""
                continue
            }
            i++
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length) {
                out[name] = ""
                break
            }
            if (raw[i] == '"' || raw[i] == '\'') {
                val quote = raw[i]
                i++
                val valueStart = i
                while (i < raw.length && raw[i] != quote) i++
                out[name] = decode(raw.substring(valueStart, i))
                if (i < raw.length) i++
            } else {
                val valueStart = i
                while (i < raw.length && !raw[i].isWhitespace()) i++
                out[name] = decode(raw.substring(valueStart, i))
            }
        }
        return out
    }

    private fun decode(text: String): String {
        if (!text.contains('&')) return text
        return text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }

    private class Frame(
        val tag: String,
        val attrs: Map<String, String> = emptyMap(),
    ) {
        val children = ArrayList<Frame>()
        val text = StringBuilder()

        fun toPiece(): Piece = Piece(tag, attrs, text.toString(), children.map { it.toPiece() })
    }

    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )
}
