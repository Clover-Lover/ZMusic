package com.kite.zmusic.plugin

/**
 * 版本字符串的切分方式与 `betterncm.ncm.getNCMVersion` / `getNCMBuild` 相同。
 * `parseInt` 只取开头的十进制数字。
 */
internal object BetterNcmVersions {
    fun packageVersion(raw: String?): String = raw?.takeIf { it.isNotEmpty() } ?: "0000000"

    fun fullVersion(raw: String?): String = raw?.takeIf { it.isNotEmpty() } ?: "0.0.0.0"

    fun version(full: String): String {
        val dot = full.lastIndexOf('.')
        if (dot < 0) throw IllegalArgumentException("no dot")
        return full.substring(0, dot)
    }

    fun build(full: String): Int? {
        val dot = full.lastIndexOf('.')
        val tail = if (dot < 0) full else full.substring(dot + 1)
        return jsParseInt(tail)
    }

    fun jsParseInt(text: String): Int? {
        var i = 0
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return null
        var sign = 1
        if (text[i] == '+' || text[i] == '-') {
            if (text[i] == '-') sign = -1
            i++
        }
        if (i >= text.length || text[i] !in '0'..'9') return null
        var n = 0L
        while (i < text.length && text[i] in '0'..'9') {
            n = n * 10 + (text[i] - '0')
            if (n > Int.MAX_VALUE.toLong() + 1) return null
            i++
        }
        val value = n * sign
        if (value > Int.MAX_VALUE || value < Int.MIN_VALUE) return null
        return value.toInt()
    }
}

internal object BetterNcmPlaying {
    fun song(snapshot: PluginPlaybackSnapshot): Map<String, Any?>? {
        val track = snapshot.track ?: return null
        return linkedMapOf(
            "data" to linkedMapOf(
                "id" to track.id,
                "name" to track.name,
                "artists" to artistEntries(track.artists),
                "album" to linkedMapOf(
                    "name" to track.album.orEmpty(),
                    "picUrl" to track.coverUrl.orEmpty(),
                ),
                "duration" to track.durationMs,
                "coverUrl" to track.coverUrl,
            ),
            "from" to linkedMapOf("fm" to false),
            "playing" to snapshot.playing,
            "state" to if (snapshot.playing) 1 else 2,
            "positionMs" to snapshot.positionMs,
        )
    }

    fun artistEntries(text: String): List<Map<String, Any?>> {
        val names = text.split(Regex("\\s*/\\s*|\\s*,\\s*|\\s*、\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val picked = if (names.isEmpty() && text.isNotBlank()) listOf(text.trim()) else names
        return picked.map { linkedMapOf("name" to it) }
    }

    fun brief(song: Map<String, Any?>): Map<String, Any?> {
        val data = song["data"] as Map<*, *>
        val from = song["from"] as Map<*, *>
        val type = if (from["fm"] == true) "fm" else "normal"
        return linkedMapOf(
            "id" to data["id"],
            "title" to data["name"],
            "type" to type,
        )
    }
}

internal data class BetterNcmLoadPlugin(
    val slug: String,
    val loadBefore: List<String> = emptyList(),
    val loadAfter: List<String> = emptyList(),
)

internal class BetterNcmDependencyError(val slug: String) : IllegalStateException(slug)

internal object BetterNcmLoadOrder {
    fun sort(plugins: List<BetterNcmLoadPlugin>): List<String> {
        val adj = LinkedHashMap<String, MutableList<String>>()
        for (plugin in plugins) adj.getOrPut(plugin.slug) { ArrayList() }
        for (plugin in plugins) {
            for (dep in plugin.loadBefore) {
                val next = adj[dep] ?: throw BetterNcmDependencyError(dep)
                next.add(plugin.slug)
            }
            for (dep in plugin.loadAfter) {
                adj.getValue(plugin.slug).add(dep)
            }
        }
        val visited = HashMap<String, Boolean>()
        val order = LinkedHashMap<String, Int>()
        var n = adj.keys.size - 1
        for (slug in adj.keys) {
            if (visited[slug] != true) {
                n = dfs(slug, n, adj, visited, order)
            }
        }
        return order.keys.toList()
    }

    private fun dfs(
        slug: String,
        n: Int,
        adj: Map<String, List<String>>,
        visited: MutableMap<String, Boolean>,
        order: LinkedHashMap<String, Int>,
    ): Int {
        visited[slug] = true
        val neighbors = adj[slug] ?: throw BetterNcmDependencyError(slug)
        var left = n
        for (neighbor in neighbors) {
            if (visited[neighbor] != true) {
                left = dfs(neighbor, left, adj, visited, order)
            }
        }
        order[slug] = left
        return left - 1
    }
}

internal data class BetterNcmManifest(
    val name: String,
    val slug: String,
    val version: String,
    val injects: Map<String, List<String>>,
    val loadBefore: List<String>,
    val loadAfter: List<String>,
    val startupScript: String? = "startup_script.js",
) {
    fun mainInjects(): List<String> = injects["Main"].orEmpty()

    fun toJsMap(): Map<String, Any?> = linkedMapOf(
        "manifest_version" to 1,
        "name" to name,
        "slug" to slug,
        "version" to version,
        "injects" to injects.mapValues { (_, files) -> files.map { linkedMapOf("file" to it) } },
        "loadBefore" to loadBefore,
        "loadAfter" to loadAfter,
    )
}

internal object BetterNcmManifests {
    fun versionCode(label: String): Int {
        val nums = Regex("\\d+").findAll(label).map { it.value.toIntOrNull() ?: 0 }.take(3).toList()
        if (nums.isEmpty()) return 1
        val major = nums.getOrElse(0) { 0 }.coerceIn(0, 99)
        val minor = nums.getOrElse(1) { 0 }.coerceIn(0, 99)
        val patch = nums.getOrElse(2) { 0 }.coerceIn(0, 99)
        return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
    }

    fun slugFromName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 ]"), "").replace(" ", "-")

    fun parse(text: String): BetterNcmManifest? {
        val obj = PluginJson.parseObject(text) ?: return null
        val manifestVersion = when (val v = obj["manifest_version"]) {
            is Int -> v
            is Long -> v.toInt()
            else -> return null
        }
        if (manifestVersion != 1) return null
        val name = obj["name"] as? String ?: return null
        if (name.isEmpty()) return null
        val explicit = (obj["slug"] as? String)?.takeIf { it.isNotEmpty() }
        val slug = explicit ?: slugFromName(name)
        if (slug.isEmpty()) return null
        val version = when (val v = obj["version"]) {
            is String -> v
            is Int -> v.toString()
            is Long -> v.toString()
            null -> return null
            else -> return null
        }
        val injects = parseInjects(obj["injects"]) ?: return null
        return BetterNcmManifest(
            name = name,
            slug = slug,
            version = version,
            injects = injects,
            loadBefore = stringList(obj["loadBefore"]),
            loadAfter = stringList(obj["loadAfter"]),
            startupScript = startupScript(obj),
        )
    }

    fun read(file: java.io.File): BetterNcmManifest? {
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return parse(text)
    }

    private fun parseInjects(raw: Any?): Map<String, List<String>>? {
        if (raw == null) return emptyMap()
        val obj = raw as? Map<*, *> ?: return null
        val out = LinkedHashMap<String, List<String>>()
        for ((key, value) in obj) {
            val page = key as? String ?: return null
            val items = value as? List<*> ?: return null
            val files = ArrayList<String>()
            for (item in items) {
                val file = when (item) {
                    is String -> item
                    is Map<*, *> -> item["file"] as? String
                    else -> return null
                } ?: return null
                val rel = PluginPackageRules.normalizeRel(file) ?: return null
                if (!rel.endsWith(".js")) continue
                files.add(rel)
            }
            out[page] = files
        }
        return out
    }

    private fun startupScript(obj: Map<String, Any?>): String? {
        if (!obj.containsKey("startup_script")) return "startup_script.js"
        val raw = obj["startup_script"] as? String ?: return null
        return PluginPackageRules.normalizeRel(raw)?.takeIf { it.endsWith(".js") }
    }

    private fun stringList(raw: Any?): List<String> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { it as? String }
    }
}

/**
 * 连续调用只保留最后一次。等待时间从最后一次调用起算。
 * 触发时交给回调的是最后一次调用的参数列表（一个参数，不是展开后的形参）。
 */
internal class BetterNcmDebounce(private val waitMs: Long) {
    private var generation = 0
    private var fireAt = Long.MIN_VALUE
    private var args: List<Any?>? = null

    fun call(nowMs: Long, callArgs: List<Any?>) {
        generation++
        args = callArgs
        fireAt = nowMs + waitMs
    }

    fun poll(nowMs: Long): List<Any?>? {
        val pending = args ?: return null
        if (nowMs < fireAt) return null
        args = null
        return pending
    }
}
