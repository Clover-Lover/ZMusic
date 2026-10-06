package com.kite.zmusic.plugin

/**
 * `betterncm.utils.dom` / `waitForElement` 用的文档。
 * 选择器认标签、`.class`、`#id`，空格表示后代。节点同时镜像到该插件隔离的 WebView。
 */
internal class BetterNcmDocument {
    class Node(
        val id: Int,
        val tag: String,
    ) {
        val classes = LinkedHashSet<String>()
        val style = LinkedHashMap<String, String>()
        val attrs = LinkedHashMap<String, String>()
        val children = ArrayList<Int>()
        var text: String = ""
    }

    private var seq = 0
    private val nodes = LinkedHashMap<Int, Node>()

    fun create(tag: String, settings: Map<String, Any?>, childIds: List<Int>): Int? {
        val name = tag.trim().lowercase()
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() }) return null
        val id = ++seq
        val node = Node(id, name)
        when (val classes = settings["class"]) {
            is List<*> -> classes.forEach { item ->
                val text = item as? String ?: return@forEach
                if (text.isNotEmpty()) node.classes.add(text)
            }
            is String -> classes.split(Regex("\\s+")).forEach { text ->
                if (text.isNotEmpty()) node.classes.add(text)
            }
        }
        val style = settings["style"]
        if (style is Map<*, *>) {
            style.forEach { (key, value) ->
                val nameKey = key as? String ?: return@forEach
                if (value == null || value == false) return@forEach
                node.style[nameKey] = value.toString()
            }
        }
        settings.forEach { (key, value) ->
            if (key == "class" || key == "style") return@forEach
            if (value == null || value == false) return@forEach
            when (key) {
                "innerText", "textContent" -> node.text = value.toString()
                else -> if (value is String || value is Number || value == true) {
                    node.attrs[key] = if (value == true) "true" else value.toString()
                }
            }
        }
        for (child in childIds) {
            if (nodes[child] == null) return null
            node.children.add(child)
        }
        nodes[id] = node
        return id
    }

    fun append(parentId: Int, childId: Int): Boolean {
        val parent = nodes[parentId] ?: return false
        if (nodes[childId] == null || parentId == childId) return false
        if (childId in parent.children) return true
        parent.children.add(childId)
        return true
    }

    fun query(selector: String, scope: Int? = null): Int? = queryAll(selector, scope).firstOrNull()

    fun queryAll(selector: String, scope: Int? = null): List<Int> {
        val parts = selector.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val allowed = if (scope == null) null else descendantIds(scope) ?: return emptyList()
        return nodes.values.map { it.id }.filter { id ->
            (allowed == null || id in allowed) && matchesChain(nodes.getValue(id), parts)
        }
    }

    fun setHtml(id: Int, html: String): Boolean {
        val node = nodes[id] ?: return false
        for (child in node.children.toList()) {
            nodes[child]?.let { detach(it) }
            nodes.remove(child)
        }
        node.children.clear()
        node.text = ""
        for (piece in BetterNcmHtml.parse(html)) {
            val child = materialize(piece) ?: continue
            node.children.add(child)
        }
        return true
    }

    fun addClass(id: Int, name: String): Boolean {
        val node = nodes[id] ?: return false
        val clean = name.trim()
        if (clean.isEmpty()) return false
        node.classes.add(clean)
        return true
    }

    fun removeClass(id: Int, name: String): Boolean {
        val node = nodes[id] ?: return false
        return node.classes.remove(name.trim())
    }

    fun hasClass(id: Int, name: String): Boolean = nodes[id]?.classes?.contains(name) == true

    fun setClassName(id: Int, value: String): Boolean {
        val node = nodes[id] ?: return false
        node.classes.clear()
        value.split(Regex("\\s+")).forEach { text ->
            if (text.isNotEmpty()) node.classes.add(text)
        }
        return true
    }

    fun setAttr(id: Int, key: String, value: String): Boolean {
        val node = nodes[id] ?: return false
        if (key.isEmpty()) return false
        node.attrs[key] = value
        return true
    }

    fun setText(id: Int, text: String): Boolean {
        val node = nodes[id] ?: return false
        node.text = text
        return true
    }

    fun clearChildren(id: Int): Boolean {
        val node = nodes[id] ?: return false
        node.children.clear()
        return true
    }

    fun node(id: Int): Node? = nodes[id]

    private fun matches(node: Node, selector: String): Boolean {
        var rest = selector.trim()
        if (rest.isEmpty()) return false
        var tag: String? = null
        if (!rest.startsWith('.') && !rest.startsWith('#')) {
            val end = rest.indexOfAny(charArrayOf('.', '#'))
            if (end < 0) return node.tag.equals(rest, ignoreCase = true)
            tag = rest.substring(0, end)
            rest = rest.substring(end)
        }
        var id: String? = null
        val classes = ArrayList<String>()
        while (rest.isNotEmpty()) {
            when {
                rest.startsWith('.') -> {
                    rest = rest.removePrefix(".")
                    val end = rest.indexOfAny(charArrayOf('.', '#'))
                    val name = if (end < 0) rest else rest.substring(0, end)
                    if (name.isEmpty()) return false
                    classes.add(name)
                    rest = if (end < 0) "" else rest.substring(end)
                }
                rest.startsWith('#') -> {
                    rest = rest.removePrefix("#")
                    val end = rest.indexOfAny(charArrayOf('.', '#'))
                    val name = if (end < 0) rest else rest.substring(0, end)
                    if (name.isEmpty() || id != null) return false
                    id = name
                    rest = if (end < 0) "" else rest.substring(end)
                }
                else -> return false
            }
        }
        if (tag != null && !node.tag.equals(tag, ignoreCase = true)) return false
        if (id != null && node.attrs["id"] != id) return false
        if (classes.any { it !in node.classes }) return false
        return tag != null || id != null || classes.isNotEmpty()
    }

    private fun matchesChain(node: Node, parts: List<String>): Boolean {
        if (!matches(node, parts.last())) return false
        var index = parts.lastIndex - 1
        var cursor = parentOf(node.id)
        while (cursor != null && index >= 0) {
            if (matches(cursor, parts[index])) index--
            cursor = parentOf(cursor.id)
        }
        return index < 0
    }

    private fun parentOf(id: Int): Node? {
        for (node in nodes.values) {
            if (id in node.children) return node
        }
        return null
    }

    private fun descendantIds(rootId: Int): Set<Int>? {
        if (nodes[rootId] == null) return null
        val out = LinkedHashSet<Int>()
        fun walk(id: Int) {
            val node = nodes[id] ?: return
            for (child in node.children) {
                if (out.add(child)) walk(child)
            }
        }
        walk(rootId)
        return out
    }

    private fun detach(node: Node) {
        for (child in node.children) {
            nodes[child]?.let { detach(it) }
            nodes.remove(child)
        }
    }

    private fun materialize(piece: BetterNcmHtml.Piece): Int? {
        val settings = LinkedHashMap<String, Any?>()
        piece.attrs.forEach { (key, value) -> settings[key] = value }
        if (piece.text.isNotEmpty()) settings["textContent"] = piece.text
        val id = create(piece.tag, settings, emptyList()) ?: return null
        for (child in piece.children) {
            val childId = materialize(child) ?: continue
            append(id, childId)
        }
        return id
    }
}
