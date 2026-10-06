package com.kite.zmusic.workshop

import com.kite.zmusic.plugin.BetterNcmManifests
import com.kite.zmusic.plugin.PluginJson

/**
 * BetterNCM 插件市场。目录是 `plugins.json`，预览图和插件包相对这次实际打开的根地址。
 * 用户在设置里填的地址先试。打不开时再试能直接返回 JSON 的镜像，避免停在跳转或拦截页。
 */
internal object BetterNcmMarket {
    const val BASE_URL = "https://gitcode.net/qq_21551787/bncm-plugin-packed/-/raw/master/"
    const val JSDELIVR_URL = "https://cdn.jsdelivr.net/gh/BetterNCM/BetterNCM-Packed-Plugins@master/"
    const val GITHUB_URL = "https://raw.githubusercontent.com/BetterNCM/BetterNCM-Packed-Plugins/master/"

    val sources: List<String>
        get() = catalogSources(com.kite.zmusic.config.BetterNcmMarketConfig.baseUrl)

    /** 用户配置的根地址在前。后面是同一份目录里实际能打开的地址，不重复。 */
    fun catalogSources(custom: String): List<String> {
        val builtin = listOf(JSDELIVR_URL, BASE_URL, GITHUB_URL)
        val normalized = custom.trim().let { if (it.isEmpty()) "" else if (it.endsWith("/")) it else "$it/" }
        if (normalized.isEmpty()) return builtin
        return listOf(normalized) + builtin.filter {
            !it.trimEnd('/').equals(normalized.trimEnd('/'), ignoreCase = true)
        }
    }

    fun catalogUrl(base: String): String = join(base, "plugins.json")

    fun join(base: String, relative: String): String {
        val path = relative.trim()
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            return path
        }
        val root = if (base.endsWith("/")) base else "$base/"
        return root + path.removePrefix("/")
    }

    fun parse(text: String, base: String): List<BetterNcmRemote> {
        val array = PluginJson.parse(text) as? List<*> ?: return emptyList()
        val out = ArrayList<BetterNcmRemote>(array.size)
        for (raw in array) {
            val item = raw as? Map<*, *> ?: continue
            parseOne(item, base)?.let { out.add(it) }
        }
        return out
    }

    fun page(all: List<BetterNcmRemote>, page: Int, perPage: Int, q: String): WorkshopPage<WorkshopPluginCard> {
        val query = q.trim()
        val filtered = if (query.isEmpty()) {
            all
        } else {
            all.filter { remote ->
                val card = remote.card
                card.name.contains(query, ignoreCase = true) ||
                    card.id.contains(query, ignoreCase = true) ||
                    card.description.contains(query, ignoreCase = true) ||
                    card.author.contains(query, ignoreCase = true) ||
                    remote.repo.contains(query, ignoreCase = true)
            }
        }
        val size = perPage.coerceAtLeast(1)
        val index = (page.coerceAtLeast(1) - 1) * size
        if (index >= filtered.size) {
            return WorkshopPage(ok = true, error = "", more = false, entries = emptyList())
        }
        val end = (index + size).coerceAtMost(filtered.size)
        return WorkshopPage(
            ok = true,
            error = "",
            more = end < filtered.size,
            entries = filtered.subList(index, end).map { it.card },
        )
    }

    private fun parseOne(item: Map<*, *>, base: String): BetterNcmRemote? {
        val slug = text(item["slug"])
        if (slug.isEmpty() || flag(item["hide"])) return null
        val name = text(item["name"]).ifEmpty { slug }
        val preview = text(item["preview"])
        val file = text(item["file-url"]).ifEmpty { text(item["file"]) }
        val updatedRaw = long(item["update_time"])
        val updated = if (updatedRaw in 1..9_999_999_999L) updatedRaw * 1000 else updatedRaw
        val card = WorkshopPluginCard(
            id = slug,
            name = name,
            version = BetterNcmManifests.versionCode(text(item["version"])),
            description = text(item["description"]),
            coverUrl = if (preview.isEmpty()) "" else join(base, preview),
            author = text(item["author"]),
            publisherUid = "",
            ratingAvg = 0.0,
            ratingCount = 0,
            downloads = long(item["stars"]).toInt().coerceAtLeast(0),
            updatedAt = updated,
            engineMin = 0,
            engineMax = null,
            category = WorkshopCategories.BETTERNCM,
            versionLabel = text(item["version"]),
        )
        return BetterNcmRemote(
            card = card,
            fileUrl = if (file.isEmpty()) "" else join(base, file),
            repo = text(item["repo"]),
        )
    }

    fun readmeCandidates(repo: String): List<String> {
        val slug = repo.trim()
            .removePrefix("https://github.com/")
            .removeSuffix(".git")
            .trim('/')
        if (!slug.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) return emptyList()
        return listOf("master", "main").map { branch ->
            "https://cdn.jsdelivr.net/gh/$slug@$branch/README.md"
        }
    }

    private fun text(value: Any?): String = (value as? String)?.trim().orEmpty()

    private fun flag(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        is Number -> value.toInt() != 0
        else -> false
    }

    private fun long(value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Double -> value.toLong()
        else -> 0L
    }
}

internal data class BetterNcmRemote(
    val card: WorkshopPluginCard,
    val fileUrl: String,
    val repo: String,
) {
    fun toDetail(readme: String = ""): WorkshopPluginDetail {
        val about = buildString {
            val body = readme.trim()
            if (body.isNotEmpty()) append(body)
            val source = repo.trim().trim('/')
            if (source.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("https://github.com/").append(source)
            }
        }
        return WorkshopPluginDetail(
            card = card,
            readme = about,
            readmeTruncated = false,
            sizeBytes = 0L,
            sha256 = "0".repeat(64),
            signature = WorkshopSignature(kid = "", alg = "", sig = ""),
            myRating = null,
            packageUrl = fileUrl,
        )
    }
}
