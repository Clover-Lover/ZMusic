package com.kite.zmusic.plugin

/**
 * 在对象图上查找函数和值。
 * 深度、跳过 `window.betterncm`、字符串查找会看到原型链、以及 `searchForData` 对子对象改走函数查找，
 * 这些可见结果与 BetterNCM 的 `betterncm.ncm` 一致。
 */
internal object BetterNcmSearch {
    const val MAX_PATH = 10

    enum class Kind { MISSING, OBJECT, FUNCTION, OTHER, NULL }

    data class Hit(
        val path: List<String>,
        val key: String,
        val kind: Kind,
        val source: String?,
    )

    interface Cursor {
        val isWindow: Boolean
        val identity: Any
        fun ownKeys(): List<String>
        fun chainKeys(): List<String>
        fun kind(key: String): Kind
        fun child(key: String): Cursor?
        fun source(key: String): String?
        fun test(key: String, predicate: (Any?) -> Boolean): Boolean
    }

    fun findNativeFunction(root: Cursor, identifiers: String): String? {
        for (key in root.chainKeys()) {
            val kind = root.kind(key)
            if (kind == Kind.MISSING) continue
            if (kind == Kind.NULL) throw IllegalStateException("null")
            val text = root.source(key) ?: continue
            if (identifiers.all { text.contains(it) }) return key
        }
        return null
    }

    fun findApiFunction(
        root: Cursor?,
        name: String?,
        predicate: ((Any?) -> Boolean)?,
        path: MutableList<String> = mutableListOf("window"),
        seen: MutableSet<Any> = HashSet(),
    ): Hit? {
        val found = ArrayList<Hit>()
        searchApi(root, name, predicate, path, seen, found, stopAtFirst = true)
        return found.firstOrNull()
    }

    fun searchApiFunction(
        root: Cursor?,
        name: String?,
        predicate: ((Any?) -> Boolean)?,
        path: MutableList<String> = mutableListOf("window"),
        seen: MutableSet<Any> = HashSet(),
    ): List<Hit> {
        val found = ArrayList<Hit>()
        searchApi(root, name, predicate, path, seen, found, stopAtFirst = false)
        return found
    }

    /**
     * 子对象不继续按「数据」下钻，而是改走函数查找。当前层的非对象值才交给谓词。
     */
    fun searchForData(
        root: Cursor?,
        predicate: (Any?) -> Boolean,
        path: MutableList<String> = mutableListOf("window"),
        seen: MutableSet<Any> = HashSet(),
    ): List<Hit> {
        if (root == null) return emptyList()
        if (!seen.add(root.identity)) return emptyList()
        val found = ArrayList<Hit>()
        try {
            if (path.size < MAX_PATH) {
                for (key in root.ownKeys()) {
                    if (skipSelf(path, root, key)) continue
                    when (root.kind(key)) {
                        Kind.OBJECT -> {
                            val child = root.child(key) ?: continue
                            if (child.identity in seen) continue
                            path.add(key)
                            searchApi(child, null, predicate, path, seen, found, stopAtFirst = false)
                            path.removeAt(path.lastIndex)
                        }
                        Kind.NULL -> Unit
                        Kind.MISSING -> Unit
                        else -> {
                            if (root.test(key, predicate)) {
                                found.add(Hit(path.toList(), key, root.kind(key), root.source(key)))
                            }
                        }
                    }
                }
            }
        } finally {
            seen.remove(root.identity)
        }
        return found
    }

    private fun searchApi(
        root: Cursor?,
        name: String?,
        predicate: ((Any?) -> Boolean)?,
        path: MutableList<String>,
        seen: MutableSet<Any>,
        found: MutableList<Hit>,
        stopAtFirst: Boolean,
    ) {
        if (root == null) return
        if (!seen.add(root.identity)) return
        try {
            if (name != null) {
                if (root.kind(name) == Kind.FUNCTION) {
                    found.add(Hit(path.toList(), name, Kind.FUNCTION, root.source(name)))
                    if (stopAtFirst) return
                }
            } else if (predicate != null) {
                for (key in root.ownKeys()) {
                    if (root.kind(key) == Kind.FUNCTION && root.test(key, predicate)) {
                        found.add(Hit(path.toList(), key, Kind.FUNCTION, root.source(key)))
                        if (stopAtFirst) return
                    }
                }
            }
            if (stopAtFirst && found.isNotEmpty()) return
            if (path.size < MAX_PATH) {
                for (key in root.ownKeys()) {
                    if (root.kind(key) != Kind.OBJECT) continue
                    if (skipSelf(path, root, key)) continue
                    val child = root.child(key) ?: continue
                    if (child.identity in seen) continue
                    path.add(key)
                    searchApi(child, name, predicate, path, seen, found, stopAtFirst)
                    path.removeAt(path.lastIndex)
                    if (stopAtFirst && found.isNotEmpty()) return
                }
            }
        } finally {
            seen.remove(root.identity)
        }
    }

    private fun skipSelf(path: List<String>, root: Cursor, key: String): Boolean =
        path.size == 1 && root.isWindow && key == "betterncm"
}

internal class MemCursor(
    private val props: List<Pair<String, MemProp>>,
    override val isWindow: Boolean = false,
    private val proto: MemCursor? = null,
) : BetterNcmSearch.Cursor {
    override val identity: Any = this

    override fun ownKeys(): List<String> = props.map { it.first }

    override fun chainKeys(): List<String> {
        val seen = LinkedHashSet<String>()
        var node: MemCursor? = this
        while (node != null) {
            for ((key, _) in node.props) seen.add(key)
            node = node.proto
        }
        return seen.toList()
    }

    override fun kind(key: String): BetterNcmSearch.Kind = when (val prop = lookup(key)) {
        null -> BetterNcmSearch.Kind.MISSING
        is MemProp.Obj -> BetterNcmSearch.Kind.OBJECT
        is MemProp.Fn -> BetterNcmSearch.Kind.FUNCTION
        MemProp.Null -> BetterNcmSearch.Kind.NULL
        is MemProp.Leaf -> BetterNcmSearch.Kind.OTHER
    }

    override fun child(key: String): BetterNcmSearch.Cursor? =
        (lookup(key) as? MemProp.Obj)?.node

    override fun source(key: String): String? = when (val prop = lookup(key)) {
        is MemProp.Fn -> prop.source
        is MemProp.Leaf -> prop.text
        is MemProp.Obj -> "[object Object]"
        else -> null
    }

    override fun test(key: String, predicate: (Any?) -> Boolean): Boolean {
        val prop = lookup(key) ?: return false
        val handle: Any? = when (prop) {
            is MemProp.Fn -> prop.source
            is MemProp.Leaf -> prop.text
            is MemProp.Obj -> prop.node
            MemProp.Null -> null
        }
        return predicate(handle)
    }

    private fun lookup(key: String): MemProp? {
        var node: MemCursor? = this
        while (node != null) {
            val hit = node.props.firstOrNull { it.first == key }?.second
            if (hit != null) return hit
            node = node.proto
        }
        return null
    }
}

internal sealed class MemProp {
    class Obj(val node: MemCursor) : MemProp()
    class Fn(val source: String) : MemProp()
    class Leaf(val text: String) : MemProp()
    data object Null : MemProp()
}
