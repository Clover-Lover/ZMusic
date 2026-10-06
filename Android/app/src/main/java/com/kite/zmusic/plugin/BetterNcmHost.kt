package com.kite.zmusic.plugin

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/**
 * 一个插件一份沙盒。文件接口只落在这份沙盒里。
 * `C:`、`D:` 等盘符是沙盒内的文件夹，不是手机上的真实磁盘。
 */
internal class BetterNcmHost(
    val dataRoot: File,
    val pluginId: String,
    val pluginRoot: File,
    private val lightTheme: () -> Boolean = { true },
    private val playback: () -> PluginPlaybackSnapshot = { PluginPlaybackSnapshot.EMPTY },
    private val appver: () -> String? = { null },
    private val packageVersion: () -> String? = { null },
    private val openUrl: (String) -> Boolean = { false },
    private val openFile: (String, String) -> String = { _, _ -> "" },
    private val reloadSelf: () -> Boolean = { false },
    private val mirrorDom: (String) -> Unit = {},
    private val eapiCall: (String) -> String = { """{"code":502,"msg":"unsupported"}""" },
    private val themeLook: (BetterNcmLook) -> Unit = {},
    private val control: (String, String) -> String = { _, _ -> "false" },
    private val fetchExternal: (String, String, String, Map<String, String>) -> Map<String, Any?> =
        { _, _, _, _ -> linkedMapOf("status" to 0, "text" to "", "b64" to "") },
) {
    val document = BetterNcmDocument()

    init {
        dataRoot.mkdirs()
        pluginRoot.mkdirs()
        File(dataRoot, BetterNcmPaths.DATA_DRIVE).mkdirs()
    }

    fun dataPathNative(): String = BetterNcmPaths.DATA_PATH_NATIVE

    fun dataPathPresented(): String = BetterNcmPaths.presentDataPath(dataPathNative())

    fun pluginPath(): String = BetterNcmPaths.pluginPath()

    fun isLightTheme(): Boolean = lightTheme()

    fun ncmPackageVersion(): String = BetterNcmVersions.packageVersion(packageVersion())

    fun ncmFullVersion(): String = BetterNcmVersions.fullVersion(appver())

    fun ncmVersion(): String = BetterNcmVersions.version(ncmFullVersion())

    fun ncmBuild(): Int? = BetterNcmVersions.build(ncmFullVersion())

    fun playingSong(): Map<String, Any?>? = BetterNcmPlaying.song(playback())

    fun channel(name: String, argsJson: String): String {
        val args = PluginJson.parse(argsJson) as? List<*> ?: emptyList<Any?>()
        return when (name) {
            "audioplayer.seek" -> {
                val ms = BetterNcmTransport.seekMs(args) ?: return "false"
                control("seek", ms.toString())
            }
            "audioplayer.setVolume" -> {
                val level = BetterNcmTransport.unit(args, 2) ?: return "false"
                control("volume", level.toString())
            }
            "audioplayer.play", "audioplayer.resume" -> control("play", "")
            "audioplayer.pause" -> control("pause", "")
            "audioplayer.next" -> control("next", "")
            "audioplayer.prev", "audioplayer.previous" -> control("prev", "")
            "os.navigateExternal" -> {
                val url = args.firstOrNull()?.toString().orEmpty()
                if (openExternal(url)) "true" else "false"
            }
            else -> "null"
        }
    }

    fun cmder(name: String, argsJson: String): String = when (name) {
        "os.querySystemFonts" -> PluginJson.stringify(BetterNcmFonts.names(fontFiles()))
        else -> channel(name, argsJson)
    }

    private fun fontFiles(): List<String> =
        java.io.File("/system/fonts").list()?.toList().orEmpty()

    fun playerRate(raw: String?): String {
        if (raw == null) return control("rate.get", "")
        val rate = BetterNcmTransport.rate(raw) ?: return control("rate.get", "")
        control("rate", rate.toString())
        return rate.toString()
    }

    fun playerVolume(): String = control("volume.get", "")

    fun fetch(method: String, url: String, body: String, headersJson: String): Map<String, Any?> {
        routeKnownApi(url)?.let { return it }
        val headers = LinkedHashMap<String, String>()
        PluginJson.parseObject(headersJson)?.forEach { (key, value) ->
            if (value != null) headers[key] = value.toString()
        }
        val reply = fetchExternal(method, url, body, headers)
        val status = when (val code = reply["status"]) {
            is Int -> code
            is Long -> code.toInt()
            is Double -> code.toInt()
            else -> 0
        }
        return linkedMapOf(
            "status" to status,
            "text" to (reply["text"] as? String).orEmpty(),
            "b64" to (reply["b64"] as? String).orEmpty(),
        )
    }

    private fun routeKnownApi(url: String): Map<String, Any?>? {
        if (BetterNcmEapiRoutes.kind(url) == BetterNcmEapiRoutes.Kind.OTHER) return null
        val cut = url.substringBefore('#')
        val queryAt = cut.indexOf('?')
        val path = if (queryAt < 0) cut else cut.substring(0, queryAt)
        val query = if (queryAt < 0) "" else cut.substring(queryAt + 1)
        val payload = PluginJson.stringify(
            linkedMapOf(
                "url" to path,
                "query" to decodeQuery(query),
                "data" to emptyMap<String, Any?>(),
            ),
        )
        val text = eapi(payload)
        if (text.isBlank()) return linkedMapOf("status" to 502, "text" to "", "b64" to "")
        return linkedMapOf("status" to 200, "text" to text, "b64" to "")
    }

    fun mount(path: String): String {
        val place = locate(path) ?: return ""
        val file = when (place) {
            BetterNcmPaths.Place.PluginsMount -> pluginRoot
            is BetterNcmPaths.Place.OnDisk -> place.file
        }
        if (!file.exists()) file.mkdirs()
        return file.toURI().toString().trimEnd('/')
    }

    fun eapi(payload: String): String = eapiCall(payload)

    fun applyStyle(id: String, css: String, extraJson: String = "{}") {
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "").ifEmpty { "sheet" }
        val extra = PluginJson.parseObject(extraJson)
        val vars = extra?.get("vars") as? Map<*, *>
        val flags = stringList(extra?.get("flags"))
        val unflags = stringList(extra?.get("unflags"))
        val cssJson = PluginJson.stringify(css)
        val script = buildString {
            append("(function(){var root=document.documentElement||document.body;")
            vars?.forEach { (key, value) ->
                val name = key as? String ?: return@forEach
                append("if(root&&root.style)root.style.setProperty(")
                append(PluginJson.stringify(name))
                append(",")
                append(PluginJson.stringify(value?.toString().orEmpty()))
                append(");")
            }
            append("var body=document.body;if(body&&body.classList){")
            flags.forEach { append("body.classList.add(${PluginJson.stringify(it)});") }
            unflags.forEach { append("body.classList.remove(${PluginJson.stringify(it)});") }
            append("}")
            append("var el=document.getElementById(\"bncm-style-$safe\");")
            append("if(!el){el=document.createElement(\"style\");el.id=\"bncm-style-$safe\";")
            append("(document.head||root).appendChild(el);}")
            append("el.textContent=$cssJson;})();")
        }
        val varMap = LinkedHashMap<String, String>()
        vars?.forEach { (key, value) ->
            val name = key as? String ?: return@forEach
            varMap[name] = value?.toString().orEmpty()
        }
        themeLook(
            BetterNcmLook(
                imageUrl = BetterNcmThemeSignals.imageUrl(css, varMap),
                accent = BetterNcmThemeSignals.accent(varMap),
                frosted = BetterNcmThemeSignals.frosted(css),
            ),
        )
        mirrorDom(script)
    }

    fun cssVar(name: String, value: String) {
        if (name.isBlank()) return
        mirrorDom(
            "(function(){var root=document.documentElement||document.body;" +
                "if(root&&root.style)root.style.setProperty(" +
                "${PluginJson.stringify(name)},${PluginJson.stringify(value)});})();",
        )
    }

    fun bodyFlag(name: String, on: Boolean) {
        if (name.isBlank()) return
        val op = if (on) "add" else "remove"
        mirrorDom(
            "(function(){var body=document.body;if(body&&body.classList)body.classList.$op(" +
                "${PluginJson.stringify(name)});})();",
        )
    }

    private fun stringList(value: Any?): List<String> {
        val raw = value as? List<*> ?: return emptyList()
        return raw.mapNotNull { it as? String }
    }

    fun readDir(path: String): List<String>? {
        return when (val place = locate(path)) {
            null -> null
            BetterNcmPaths.Place.PluginsMount -> {
                pluginRoot.listFiles()?.mapNotNull { child ->
                    BetterNcmPaths.virtual(dataRoot, pluginRoot, child)
                } ?: emptyList()
            }
            is BetterNcmPaths.Place.OnDisk -> {
                val dir = place.file
                if (!dir.isDirectory) return null
                val names = dir.listFiles()?.mapNotNull { child ->
                    BetterNcmPaths.virtual(dataRoot, pluginRoot, child)
                }?.toMutableList() ?: return null
                if (isCRoot(dir) && names.none { it.equals(pluginsMountVirtual(), ignoreCase = true) }) {
                    names.add(pluginsMountVirtual())
                }
                names
            }
        }
    }

    fun readFileText(path: String): String? {
        val file = diskFile(path) ?: return null
        if (!file.isFile) return ""
        return runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: ""
    }

    fun readFile(path: String): ByteArray? {
        val file = diskFile(path) ?: return null
        if (!file.isFile) return ByteArray(0)
        return runCatching { file.readBytes() }.getOrNull() ?: ByteArray(0)
    }

    fun writeFileText(path: String, content: String): Boolean {
        val file = diskFile(path) ?: return false
        if (file.parentFile?.isDirectory != true) return false
        return runCatching {
            file.writeText(content, StandardCharsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun writeFile(path: String, content: ByteArray): Boolean {
        val file = diskFile(path) ?: return false
        if (file.parentFile?.isDirectory != true) return false
        return runCatching {
            file.writeBytes(content)
            true
        }.getOrDefault(false)
    }

    fun mkdir(path: String): Boolean {
        val place = locate(path) ?: return false
        if (place is BetterNcmPaths.Place.PluginsMount) return true
        val file = (place as BetterNcmPaths.Place.OnDisk).file
        return runCatching { file.mkdirs() || file.isDirectory }.getOrDefault(false)
    }

    fun exists(path: String): Boolean {
        return when (val place = locate(path)) {
            null -> false
            BetterNcmPaths.Place.PluginsMount -> true
            is BetterNcmPaths.Place.OnDisk -> place.file.exists()
        }
    }

    fun rename(from: String, to: String): Boolean {
        val src = diskFile(from) ?: return false
        val dst = diskFile(to) ?: return false
        if (!src.exists()) return false
        if (protectedFile(src) || protectedFile(dst)) return false
        if (dst.parentFile?.isDirectory != true) return false
        return runCatching {
            if (dst.exists() && !dst.delete()) return false
            src.renameTo(dst)
        }.getOrDefault(false)
    }

    fun remove(path: String): Boolean {
        val place = locate(path) ?: return false
        if (place is BetterNcmPaths.Place.PluginsMount) return false
        val file = (place as BetterNcmPaths.Place.OnDisk).file
        if (protectedFile(file)) return false
        if (!file.exists()) return true
        return runCatching {
            file.deleteRecursively()
            !file.exists()
        }.getOrDefault(false)
    }

    fun exec(command: String): Boolean = BetterNcmExec.run(command, this)

    fun ncmPath(): String = ""

    fun winPos(): Map<String, Int> = linkedMapOf("x" to 0, "y" to 0)

    fun showConsole(): Boolean = false

    fun setRoundedCorner(): Boolean = true

    fun reloadPlugins(): Boolean = true

    fun succeededHijacks(): List<String> = emptyList()

    fun whiteScreenshot(): ByteArray = BetterNcmImages.whitePng()

    fun reload(): Boolean = reloadSelf()

    fun openExternal(url: String): Boolean {
        val safe = BetterNcmLinks.allow(url) ?: return false
        return openUrl(safe)
    }

    fun pickFile(filter: String, initialDir: String): String = openFile(filter, initialDir)

    fun nativeCall(): Boolean = false

    fun createNode(tag: String, settingsJson: String, childrenJson: String): Int? {
        val settings = PluginJson.parse(settingsJson) as? Map<*, *> ?: emptyMap<String, Any?>()
        val childRaw = PluginJson.parse(childrenJson) as? List<*> ?: emptyList<Any?>()
        val childIds = childRaw.mapNotNull {
            when (it) {
                is Int -> it
                is Long -> it.toInt()
                is Double -> it.toInt()
                else -> null
            }
        }
        val normalized = LinkedHashMap<String, Any?>()
        settings.forEach { (key, value) ->
            if (key is String) normalized[key] = value
        }
        val id = document.create(tag, normalized, childIds) ?: return null
        mirrorDom(domScript(id))
        return id
    }

    fun appendNode(parentId: Int, childId: Int): Boolean {
        if (!document.append(parentId, childId)) return false
        mirrorDom(
            "if(window.__bncmAppend)__bncmAppend($parentId,$childId);",
        )
        return true
    }

    fun queryNode(selector: String, scope: Int? = null): Int? = document.query(selector, scope)

    fun queryAll(selector: String, scope: Int? = null): List<Int> = document.queryAll(selector, scope)

    fun setHtml(id: Int, html: String): Boolean {
        if (!document.setHtml(id, html)) return false
        fun walk(nodeId: Int) {
            mirrorDom(domScript(nodeId))
            document.node(nodeId)?.children?.forEach { walk(it) }
        }
        walk(id)
        for (styleId in document.queryAll("style", id)) {
            val css = document.node(styleId)?.text.orEmpty()
            if (css.isNotBlank()) applyStyle("html-$styleId", css)
        }
        return true
    }

    fun setClass(id: Int, name: String, on: Boolean): Boolean {
        val node = document.node(id) ?: return false
        val previous = node.classes.toList()
        val changed = if (on) document.addClass(id, name) else document.removeClass(id, name)
        if (!changed && on) return document.hasClass(id, name)
        if (node.tag == "body") syncBodyFlags(previous, node.classes.toList())
        mirrorDom(domScript(id))
        return changed || document.hasClass(id, name)
    }

    fun hasClass(id: Int, name: String): Boolean = document.hasClass(id, name)

    fun classNames(id: Int): List<String> = document.node(id)?.classes?.toList().orEmpty()

    fun setClassName(id: Int, value: String): Boolean {
        val node = document.node(id) ?: return false
        val previous = node.classes.toList()
        if (!document.setClassName(id, value)) return false
        if (node.tag == "body") syncBodyFlags(previous, node.classes.toList())
        mirrorDom(domScript(id))
        return true
    }

    fun setAttr(id: Int, key: String, value: String): Boolean {
        if (!document.setAttr(id, key, value)) return false
        mirrorDom(domScript(id))
        return true
    }

    fun setText(id: Int, text: String): Boolean {
        if (!document.setText(id, text)) return false
        mirrorDom(domScript(id))
        return true
    }

    fun clearChildren(id: Int): Boolean {
        if (!document.clearChildren(id)) return false
        mirrorDom(domScript(id))
        return true
    }

    fun nodeTag(id: Int): String = document.node(id)?.tag.orEmpty()

    fun watchStamp(path: String): Map<String, Long>? {
        val dir = when (val place = locate(path)) {
            BetterNcmPaths.Place.PluginsMount -> pluginRoot
            is BetterNcmPaths.Place.OnDisk -> place.file
            null -> return null
        }
        if (!dir.isDirectory) return null
        val out = LinkedHashMap<String, Long>()
        dir.listFiles()?.forEach { child -> out[child.name] = child.lastModified() }
        return out
    }

    private fun syncBodyFlags(previous: List<String>, next: List<String>) {
        previous.filter { it !in next }.forEach { bodyFlag(it, false) }
        next.filter { it !in previous }.forEach { bodyFlag(it, true) }
    }

    private fun domScript(id: Int): String {
        val node = document.node(id) ?: return ""
        val classes = PluginJson.stringify(node.classes.toList())
        val style = PluginJson.stringify(node.style)
        val text = PluginJson.stringify(node.text)
        val attrs = PluginJson.stringify(node.attrs)
        val children = node.children.joinToString(",")
        return "if(window.__bncmUpsert)__bncmUpsert($id,${PluginJson.stringify(node.tag)},$classes,$style,$text,$attrs,[$children]);"
    }

    fun unzip(path: String, dest: String? = null): Int {
        val zip = diskFile(path) ?: return -1
        if (!zip.isFile) return -1
        val destPath = dest?.takeIf { it.isNotEmpty() } ?: defaultUnzipDest(path)
        val folder = diskFile(destPath) ?: return -1
        return extractZip(zip, folder)
    }

    fun readConfig(key: String, defaultValue: String): String {
        val table = loadConfig()
        val value = table[key] ?: return defaultValue
        return value as? String ?: throw IllegalStateException("config")
    }

    fun writeConfig(key: String, value: String): Boolean {
        val table = LinkedHashMap(loadConfig())
        table[key] = value
        return saveConfig(table)
    }

    fun localGet(key: String): String? = loadLocal()[key]

    fun localSet(key: String, value: String): Boolean {
        val table = LinkedHashMap(loadLocal())
        table[key] = value
        return saveLocal(table)
    }

    fun localRemove(key: String): Boolean {
        val table = LinkedHashMap(loadLocal())
        table.remove(key)
        return saveLocal(table)
    }

    fun localKeys(): List<String> = loadLocal().keys.toList()

    fun localClear(): Boolean = saveLocal(emptyMap())

    fun configGet(slug: String, key: String): Any? {
        val raw = localGet(configKey(slug)) ?: return Missing
        val table = PluginJson.parse(raw)
        if (table !is Map<*, *>) return Missing
        if (!table.containsKey(key)) return Missing
        return table[key]
    }

    fun configSet(slug: String, key: String, value: Any?): Boolean {
        val raw = localGet(configKey(slug))
        val table = when {
            raw == null -> LinkedHashMap<String, Any?>()
            else -> configTable(raw) ?: return false
        }
        table[key] = value
        return localSet(configKey(slug), PluginJson.stringify(table))
    }

    fun http(method: String, url: String, body: String = "", bytes: ByteArray? = null): BetterNcmHttp {
        val q = url.indexOf('?')
        val route = if (q < 0) url else url.substring(0, q)
        val query = if (q < 0) "" else url.substring(q + 1)
        val params = decodeQuery(query)
        val verb = method.uppercase()
        return try {
            dispatch(verb, route, params, body, bytes)
        } catch (_: IllegalStateException) {
            BetterNcmHttp(500, "")
        }
    }

    private fun dispatch(
        method: String,
        route: String,
        params: Map<String, String>,
        body: String,
        bytes: ByteArray?,
    ): BetterNcmHttp {
        val path = params["path"].orEmpty()
        return when (route) {
            "/fs/read_dir" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                val names = readDir(path) ?: return BetterNcmHttp(500, "")
                BetterNcmHttp(200, PluginJson.stringify(names))
            }
            "/fs/read_file_text" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                val text = readFileText(path) ?: return BetterNcmHttp(500, "")
                BetterNcmHttp(200, text)
            }
            "/fs/read_file" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                val data = readFile(path) ?: return BetterNcmHttp(500, "")
                BetterNcmHttp(200, "", data)
            }
            "/fs/unzip_file" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                val code = unzip(path, params["dest"])
                BetterNcmHttp(200, code.toString())
            }
            "/fs/mkdir" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                if (!mkdir(path)) return BetterNcmHttp(500, "")
                BetterNcmHttp(200, "")
            }
            "/fs/exists" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, if (exists(path)) "true" else "false")
            }
            "/fs/remove" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                if (!remove(path)) return BetterNcmHttp(500, "")
                BetterNcmHttp(200, "")
            }
            "/fs/mount_dir", "/fs/mount_file" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                val mounted = mount(path)
                if (mounted.isEmpty()) return BetterNcmHttp(500, "")
                BetterNcmHttp(200, mounted)
            }
            "/fs/write_file_text" -> {
                if (method != "POST") return BetterNcmHttp(404, "")
                if (!writeFileText(path, body)) return BetterNcmHttp(500, "")
                BetterNcmHttp(200, "")
            }
            "/fs/write_file" -> {
                if (method != "POST") return BetterNcmHttp(404, "")
                val payload = bytes ?: body.toByteArray(StandardCharsets.UTF_8)
                if (!writeFile(path, payload)) return BetterNcmHttp(500, "")
                BetterNcmHttp(200, "")
            }
            "/app/datapath" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, dataPathNative())
            }
            "/app/version" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, API_VERSION)
            }
            "/app/read_config" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, readConfig(params["key"].orEmpty(), params["default"].orEmpty()))
            }
            "/app/write_config" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                if (!writeConfig(params["key"].orEmpty(), params["value"].orEmpty())) {
                    return BetterNcmHttp(500, "")
                }
                BetterNcmHttp(200, "")
            }
            "/app/is_light_theme" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, if (isLightTheme()) "true" else "false")
            }
            "/app/ncmpath" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, ncmPath())
            }
            "/app/get_win_position" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, PluginJson.stringify(winPos()))
            }
            "/app/show_console" -> BetterNcmHttp(500, "")
            "/app/set_rounded_corner" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, "")
            }
            "/app/reload_plugin" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, "")
            }
            "/app/get_succeeded_hijacks" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, "[]")
            }
            "/app/bg_screenshot" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, "", whiteScreenshot())
            }
            "/app/open_file_dialog" -> {
                if (method != "GET") return BetterNcmHttp(404, "")
                BetterNcmHttp(200, pickFile(params["filter"].orEmpty(), params["initialDir"].orEmpty()))
            }
            "/app/exec", "/app/exec_ele" -> {
                if (method != "POST") return BetterNcmHttp(404, "")
                if (!exec(body)) return BetterNcmHttp(500, "")
                BetterNcmHttp(200, "")
            }
            else -> BetterNcmHttp(404, "")
        }
    }

    private fun locate(path: String): BetterNcmPaths.Place? =
        BetterNcmPaths.resolve(path, dataRoot, pluginRoot)

    private fun diskFile(path: String): File? = when (val place = locate(path)) {
        is BetterNcmPaths.Place.OnDisk -> place.file
        else -> null
    }

    private fun isCRoot(dir: File): Boolean = sameFile(dir, File(dataRoot, BetterNcmPaths.DATA_DRIVE))

    private fun protectedFile(file: File): Boolean {
        if (sameFile(file, dataRoot) || sameFile(file, pluginRoot)) return true
        val parent = file.parentFile ?: return false
        if (!sameFile(parent, dataRoot)) return false
        val name = file.name
        return name.length == 1 && name[0].isLetter()
    }

    private fun sameFile(a: File, b: File): Boolean =
        runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(false)

    private fun pluginsMountVirtual(): String = BetterNcmPaths.pluginPath()

    private fun loadConfig(): Map<String, Any?> = loadObject(configFile())

    private fun saveConfig(table: Map<String, Any?>): Boolean = saveObject(configFile(), table)

    private fun loadLocal(): Map<String, String> {
        val obj = loadObject(localFile())
        val out = LinkedHashMap<String, String>()
        obj.forEach { (k, v) -> if (v is String) out[k] = v }
        return out
    }

    private fun saveLocal(table: Map<String, String>): Boolean = saveObject(localFile(), table)

    private fun loadObject(file: File): Map<String, Any?> {
        if (!file.isFile) return emptyMap()
        val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return emptyMap()
        val parsed = PluginJson.parseObject(text) ?: return emptyMap()
        return parsed
    }

    private fun saveObject(file: File, table: Map<String, Any?>): Boolean {
        dataRoot.mkdirs()
        return runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(PluginJson.stringify(table), StandardCharsets.UTF_8)
            if (file.exists() && !file.delete()) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    private fun configTable(raw: String): LinkedHashMap<String, Any?>? {
        if (raw.trim() == "null") return LinkedHashMap()
        val parsed = PluginJson.parse(raw)
        if (parsed == null) return null
        if (parsed is Map<*, *>) {
            return LinkedHashMap<String, Any?>().apply {
                parsed.forEach { (k, v) -> if (k is String) put(k, v) }
            }
        }
        return LinkedHashMap()
    }

    private fun configFile(): File = File(dataRoot, "config.json")

    private fun localFile(): File = File(dataRoot, "localstorage.json")

    companion object {
        const val API_VERSION = "1.3.4"
        val Missing = Any()

        fun configKey(slug: String): String = "config.betterncm.$slug"

        fun defaultUnzipDest(zipPath: String): String = "${zipPath}_extracted/"

        fun decodeQuery(query: String): Map<String, String> {
            if (query.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                val rawKey = if (eq < 0) part else part.substring(0, eq)
                val rawValue = if (eq < 0) "" else part.substring(eq + 1)
                out[urlDecode(rawKey)] = urlDecode(rawValue)
            }
            return out
        }

        private fun urlDecode(text: String): String =
            URLDecoder.decode(text, StandardCharsets.UTF_8)

        fun extractZip(zip: File, dest: File): Int {
            return try {
                dest.mkdirs()
                val root = dest.canonicalFile
                val prefix = root.path + File.separator
                ZipFile(zip).use { archive ->
                    val entries = archive.entries().toList()
                    for (entry in entries) {
                        val out = File(dest, entry.name).canonicalFile
                        if (out != root && !out.path.startsWith(prefix)) return -1
                    }
                    for (entry in entries) {
                        val out = File(dest, entry.name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                            continue
                        }
                        out.parentFile?.mkdirs()
                        archive.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                0
            } catch (_: Exception) {
                -1
            }
        }
    }
}

internal data class BetterNcmHttp(
    val status: Int,
    val text: String,
    val bytes: ByteArray? = null,
)
