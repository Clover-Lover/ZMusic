package com.kite.zmusic.plugin

import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 把 [BetterNcmHost] 挂成全局 `betterncm` 和 `plugin`。
 * 插件脚本继续使用 BetterNCM 的名字，不改成 Xuan。
 */
internal class BetterNcmRuntime(
    private val host: BetterNcmHost,
    private val scheduler: ScheduledExecutorService,
    private val onPluginThread: (Runnable) -> Unit,
) {
    private var ctx: QuickJSContext? = null
    private val timers = ConcurrentHashMap<Int, TimerHandle>()
    private val watches = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    private var timerSeq = 0
    @Volatile private var closed = false
    private val jobs = BetterNcmJobSignal()

    fun install(context: QuickJSContext) {
        ctx = context
        val global = context.getGlobalObject()
        global.setProperty("__zmusicBncm", JSCallFunction { args -> dispatch(args) })
        context.evaluate(SCRIPT, "betterncm.js")
    }

    fun bind(manifest: BetterNcmManifest?, fallbackSlug: String) {
        bindSlug(
            slug = manifest?.slug ?: fallbackSlug,
            path = host.pluginPath(),
            body = manifest?.toJsMap() ?: linkedMapOf(
                "name" to fallbackSlug,
                "slug" to (manifest?.slug ?: fallbackSlug),
                "version" to "0",
                "injects" to emptyMap<String, Any?>(),
            ),
        )
    }

    fun installBundledLibs(liblyric: String?) {
        val context = ctx ?: return
        context.evaluate(BetterNcmLibs.bootScript(), "betterncm-libs.js")
        if (liblyric.isNullOrBlank()) return
        bindSlug(
            slug = "liblyric",
            path = "C:/plugins_runtime/liblyric",
            body = linkedMapOf(
                "name" to "LibLyric",
                "slug" to "liblyric",
                "version" to "1.0.0",
                "injects" to mapOf("Main" to listOf(mapOf("file" to "index.js"))),
            ),
        )
        runCatching {
            evalInject(liblyric, "liblyric.js")
            dispatch("load")
        }
        context.evaluate(
            """
            __bncmSeal();
            (function() {
              var lib = loadedPlugins.liblyric;
              if (!lib) return;
              lib.injects = [lib];
              lib.mainPlugin = lib;
              lib.finished = true;
              lib.slug = "liblyric";
            })();
            """.trimIndent(),
            "betterncm-seal.js",
        )
    }

    private fun bindSlug(slug: String, path: String, body: Map<String, Any?>) {
        val context = ctx ?: return
        val global = context.getGlobalObject()
        global.setProperty("__bncmPluginPath", path)
        global.setProperty("__bncmSlug", slug)
        global.setProperty("__bncmManifestJson", PluginJson.stringify(body))
        context.evaluate("__bncmBind(__bncmPluginPath, __bncmManifestJson, __bncmSlug);", "betterncm-bind.js")
    }

    fun evalInject(code: String, fileName: String) {
        val context = ctx ?: return
        context.evaluate("plugin.filePath = ${PluginJson.stringify(fileName)};", fileName)
        val wrapped = "(async function(plugin){\n$code\n})(plugin);\n"
        try {
            val result = context.evaluate(wrapped, fileName)
            if (result is JSObject) result.release()
        } finally {
            pump()
        }
    }

    fun dispatch(kind: String) {
        try {
            ctx?.evaluate("__bncmDispatch(${PluginJson.stringify(kind)});", "betterncm-dispatch.js")
        } finally {
            pump()
        }
    }

    fun loadError(): String {
        val value = ctx?.evaluate("__bncmLoadError();", "betterncm-error.js")
        return value as? String ?: ""
    }

    fun close() {
        closed = true
        timers.values.forEach { it.future.cancel(false) }
        timers.clear()
        watches.values.forEach { it.cancel(false) }
        watches.clear()
    }

    private fun dispatch(args: Array<out Any?>): Any? {
        try {
            return dispatchOp(args)
        } finally {
            requestDrain()
        }
    }

    private fun dispatchOp(args: Array<out Any?>): Any? {
        val op = args.getOrNull(0) as? String ?: return null
        return when (op) {
            "fs.readDir" -> host.readDir(str(args, 1) ?: "")?.let { PluginJson.stringify(it) }
            "fs.readFileText" -> host.readFileText(str(args, 1) ?: "")
            "fs.readFile" -> host.readFile(str(args, 1) ?: "")?.let { bytes ->
                PluginJson.stringify(
                    linkedMapOf(
                        "b64" to Base64.getEncoder().encodeToString(bytes),
                        "size" to bytes.size,
                    ),
                )
            }
            "fs.writeFileText" -> host.writeFileText(str(args, 1) ?: "", str(args, 2) ?: "")
            "fs.writeFile" -> {
                val payload = decodeB64(str(args, 2)) ?: return false
                host.writeFile(str(args, 1) ?: "", payload)
            }
            "fs.mkdir" -> host.mkdir(str(args, 1) ?: "")
            "fs.exists" -> host.exists(str(args, 1) ?: "")
            "fs.remove" -> host.remove(str(args, 1) ?: "")
            "fs.rename" -> host.rename(str(args, 1) ?: "", str(args, 2) ?: "")
            "fs.unzip" -> host.unzip(str(args, 1) ?: "", str(args, 2))
            "fs.mount" -> host.mount(str(args, 1) ?: "")
            "fs.watch" -> armWatch(str(args, 1) ?: "", (long(args, 2) ?: 0L).toInt())
            "app.datapath" -> host.dataPathNative()
            "app.version" -> BetterNcmHost.API_VERSION
            "app.readConfig" -> host.readConfig(str(args, 1) ?: "", str(args, 2) ?: "")
            "app.writeConfig" -> host.writeConfig(str(args, 1) ?: "", str(args, 2) ?: "")
            "app.light" -> host.isLightTheme()
            "app.exec" -> host.exec(str(args, 1) ?: "")
            "app.ncmpath" -> host.ncmPath()
            "app.win" -> PluginJson.stringify(host.winPos())
            "app.shot" -> PluginJson.stringify(
                linkedMapOf(
                    "b64" to Base64.getEncoder().encodeToString(host.whiteScreenshot()),
                    "size" to host.whiteScreenshot().size,
                ),
            )
            "app.openFile" -> host.pickFile(str(args, 1) ?: "", str(args, 2) ?: "")
            "app.reload" -> host.reload()
            "app.reloadPlugins" -> host.reloadPlugins()
            "app.hijacks" -> PluginJson.stringify(host.succeededHijacks())
            "ncm.openUrl" -> host.openExternal(str(args, 1) ?: "")
            "native.call" -> host.nativeCall()
            "dom.create" -> host.createNode(str(args, 1) ?: "", str(args, 2) ?: "{}", str(args, 3) ?: "[]")
            "dom.append" -> host.appendNode((long(args, 1) ?: 0L).toInt(), (long(args, 2) ?: 0L).toInt())
            "dom.query" -> host.queryNode(str(args, 1) ?: "", scopeOf(args, 2))
            "dom.queryAll" -> PluginJson.stringify(host.queryAll(str(args, 1) ?: "", scopeOf(args, 2)))
            "dom.html" -> host.setHtml((long(args, 1) ?: 0L).toInt(), str(args, 2) ?: "")
            "dom.class" -> host.setClass((long(args, 1) ?: 0L).toInt(), str(args, 2) ?: "", long(args, 3) == 1L)
            "dom.classHas" -> host.hasClass((long(args, 1) ?: 0L).toInt(), str(args, 2) ?: "")
            "dom.className" -> host.setClassName((long(args, 1) ?: 0L).toInt(), str(args, 2) ?: "")
            "dom.classes" -> PluginJson.stringify(host.classNames((long(args, 1) ?: 0L).toInt()))
            "dom.attr" -> host.setAttr((long(args, 1) ?: 0L).toInt(), str(args, 2) ?: "", str(args, 3) ?: "")
            "dom.text" -> host.setText((long(args, 1) ?: 0L).toInt(), str(args, 2) ?: "")
            "dom.tag" -> host.nodeTag((long(args, 1) ?: 0L).toInt())
            "dom.clear" -> host.clearChildren((long(args, 1) ?: 0L).toInt())
            "ncm.full" -> host.ncmFullVersion()
            "ncm.version" -> host.ncmVersion()
            "ncm.package" -> host.ncmPackageVersion()
            "ncm.build" -> host.ncmBuild()
            "ncm.playing" -> host.playingSong()?.let { PluginJson.stringify(it) }
            "channel" -> host.channel(str(args, 1) ?: "", str(args, 2) ?: "[]")
            "player.rate" -> host.playerRate(str(args, 1))
            "player.volume" -> host.playerVolume()
            "fetch" -> PluginJson.stringify(
                host.fetch(str(args, 1) ?: "GET", str(args, 2) ?: "", str(args, 3) ?: "", str(args, 4) ?: "{}"),
            )
            "eapi" -> host.eapi(str(args, 1) ?: "")
            "dom.style" -> {
                host.applyStyle(str(args, 1) ?: "sheet", str(args, 2) ?: "", str(args, 3) ?: "{}")
                true
            }
            "dom.cssvar" -> {
                host.cssVar(str(args, 1) ?: "", str(args, 2) ?: "")
                true
            }
            "dom.flag" -> {
                host.bodyFlag(str(args, 1) ?: "", long(args, 2) == 1L)
                true
            }
            "ls.get" -> host.localGet(str(args, 1) ?: "")
            "ls.set" -> host.localSet(str(args, 1) ?: "", str(args, 2) ?: "")
            "ls.remove" -> host.localRemove(str(args, 1) ?: "")
            "ls.keys" -> PluginJson.stringify(host.localKeys())
            "ls.clear" -> host.localClear()
            "cmder" -> host.cmder(str(args, 1) ?: "", str(args, 2) ?: "[]")
            "cfg.get" -> configJson(str(args, 1) ?: "", str(args, 2) ?: "")
            "cfg.set" -> configSet(str(args, 1) ?: "", str(args, 2) ?: "", str(args, 3))
            "http" -> PluginJson.stringify(packHttp(host.http(str(args, 1) ?: "GET", str(args, 2) ?: "", str(args, 3) ?: "")))
            "b64.text" -> decodeB64(str(args, 1))?.toString(StandardCharsets.UTF_8)
            "utf8.b64" -> Base64.getEncoder().encodeToString((str(args, 1) ?: "").toByteArray(StandardCharsets.UTF_8))
            "timer.arm" -> armTimer(long(args, 1) ?: 0L, long(args, 2) == 1L)
            "timer.clear" -> {
                clearTimer((long(args, 1) ?: 0L).toInt())
                null
            }
            "search.native" -> searchNative(args.getOrNull(1), str(args, 2) ?: "")
            "search.oneName" -> searchJson(args, names = true, all = false, data = false)
            "search.onePred" -> searchJson(args, names = false, all = false, data = false)
            "search.allName" -> searchJson(args, names = true, all = true, data = false)
            "search.allPred" -> searchJson(args, names = false, all = true, data = false)
            "search.data" -> searchJson(args, names = false, all = true, data = true)
            else -> null
        }
    }

    private fun configJson(slug: String, key: String): String? {
        val value = host.configGet(slug, key)
        if (value === BetterNcmHost.Missing) return null
        return PluginJson.stringify(value)
    }

    private fun configSet(slug: String, key: String, json: String?): Boolean {
        if (json == null) return false
        if (json.trim() == "null") return host.configSet(slug, key, null)
        val parsed = PluginJson.parse(json) ?: return false
        return host.configSet(slug, key, parsed)
    }

    private fun packHttp(reply: BetterNcmHttp): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "status" to reply.status,
            "text" to reply.text,
        )
        if (reply.bytes != null) {
            map["b64"] = Base64.getEncoder().encodeToString(reply.bytes)
        }
        return map
    }

    private fun armTimer(ms: Long, interval: Boolean): Int {
        if (closed) return 0
        val id = synchronized(this) { ++timerSeq }
        val delay = ms.coerceAtLeast(0L)
        val future: ScheduledFuture<*> = if (interval) {
            val period = delay.coerceAtLeast(1L)
            scheduler.scheduleAtFixedRate({ fireTimer(id) }, period, period, TimeUnit.MILLISECONDS)
        } else {
            scheduler.schedule({ fireTimer(id) }, delay, TimeUnit.MILLISECONDS)
        }
        timers[id] = TimerHandle(future, interval)
        return id
    }

    private fun clearTimer(id: Int) {
        timers.remove(id)?.future?.cancel(false)
    }

    private fun fireTimer(id: Int) {
        if (closed) return
        val handle = timers[id] ?: return
        if (handle.future.isCancelled) return
        if (!handle.interval) timers.remove(id, handle)
        onPluginThread {
            if (closed) return@onPluginThread
            if (handle.interval && timers[id] == null) return@onPluginThread
            runCatching { ctx?.evaluate("__bncmFire($id);", "betterncm-timer.js") }
            pump()
        }
    }

    private class TimerHandle(val future: ScheduledFuture<*>, val interval: Boolean)

    private fun armWatch(path: String, id: Int): Boolean {
        if (closed || id <= 0) return false
        val initial = host.watchStamp(path) ?: return false
        val state = WatchState(initial)
        val future = scheduler.scheduleAtFixedRate({
            if (closed) return@scheduleAtFixedRate
            val now = host.watchStamp(path) ?: return@scheduleAtFixedRate
            val names = synchronized(state) {
                val changed = BetterNcmWatch.changed(state.stamp, now)
                state.stamp = now
                changed
            }
            if (names.isEmpty()) return@scheduleAtFixedRate
            onPluginThread {
                if (closed) return@onPluginThread
                val script = "__bncmWatchFire($id, ${PluginJson.stringify(path)}, ${PluginJson.stringify(names)});"
                runCatching { ctx?.evaluate(script, "betterncm-watch.js") }
                pump()
            }
        }, 400, 400, TimeUnit.MILLISECONDS)
        watches.put(id, future)?.cancel(false)
        return true
    }

    private class WatchState(var stamp: Map<String, Long>)

    private fun requestDrain() {
        if (closed) return
        jobs.request({ body -> onPluginThread { body() } }) { pump() }
    }

    /**
     * QuickJS 会在每次 `evaluate` 结束时跑完已经挂上的 Promise 任务。
     * 某个续体抛错会让这一轮停住，这里再开一轮，把后面的 `.then` 和 `await` 续体接着跑完。
     */
    private fun pump() {
        if (closed) return
        val context = ctx ?: return
        repeat(JOB_TURNS) {
            val failed = runCatching {
                context.evaluate("void 0;", "betterncm-jobs.js")
            }.isFailure
            if (!failed) return
        }
    }

    private fun searchNative(root: Any?, identifiers: String): String? {
        val obj = root as? JSObject ?: return null
        val context = ctx ?: return null
        val walk = JsWalk(context, obj)
        return try {
            BetterNcmSearch.findNativeFunction(walk.root, identifiers)
        } catch (_: IllegalStateException) {
            throw QuickJSException("Cannot convert undefined or null to object")
        } finally {
            walk.close()
        }
    }

    private fun searchJson(args: Array<out Any?>, names: Boolean, all: Boolean, data: Boolean): String {
        val obj = args.getOrNull(1) as? JSObject ?: return "[]"
        val context = ctx ?: return "[]"
        val path = parsePath(str(args, 3))
        val walk = JsWalk(context, obj)
        val hits = try {
            when {
                data -> {
                    val pred = args.getOrNull(2) as? JSFunction
                    BetterNcmSearch.searchForData(walk.root, jsPredicate(pred), path)
                }
                names && all -> BetterNcmSearch.searchApiFunction(walk.root, str(args, 2), null, path)
                names -> listOfNotNull(BetterNcmSearch.findApiFunction(walk.root, str(args, 2), null, path))
                all -> {
                    val pred = args.getOrNull(2) as? JSFunction
                    BetterNcmSearch.searchApiFunction(walk.root, null, jsPredicate(pred), path)
                }
                else -> {
                    val pred = args.getOrNull(2) as? JSFunction
                    listOfNotNull(BetterNcmSearch.findApiFunction(walk.root, null, jsPredicate(pred), path))
                }
            }
        } finally {
            walk.close()
        }
        return PluginJson.stringify(hits.map { hit ->
            linkedMapOf("path" to hit.path, "key" to hit.key)
        })
    }

    private fun jsPredicate(fn: JSFunction?): (Any?) -> Boolean = { value ->
        if (fn == null) false else truthy(fn.call(value))
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Int -> value != 0
        is Long -> value != 0L
        is Double -> value != 0.0 && !value.isNaN()
        is String -> value.isNotEmpty()
        else -> true
    }

    private fun parsePath(json: String?): MutableList<String> {
        if (json.isNullOrEmpty()) return mutableListOf("window")
        val parsed = PluginJson.parse(json) as? List<*> ?: return mutableListOf("window")
        val path = parsed.mapNotNull { it as? String }
        return if (path.isEmpty()) mutableListOf("window") else path.toMutableList()
    }

    private fun scopeOf(args: Array<out Any?>, index: Int): Int? =
        long(args, index)?.toInt()?.takeIf { it > 0 }

    private fun str(args: Array<out Any?>, index: Int): String? = args.getOrNull(index) as? String

    private fun long(args: Array<out Any?>, index: Int): Long? = when (val v = args.getOrNull(index)) {
        is Int -> v.toLong()
        is Long -> v
        is Double -> v.toLong()
        is Float -> v.toLong()
        else -> null
    }

    private fun decodeB64(text: String?): ByteArray? =
        text?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    private class JsWalk(private val ctx: QuickJSContext, root: JSObject) {
        private val bag = ArrayList<JSObject>()
        val root = JsCursor(ctx, root, bag, root.getPointer() == ctx.getGlobalObject().getPointer())

        fun close() {
            bag.forEach { runCatching { it.release() } }
            bag.clear()
        }
    }

    private class JsCursor(
        private val ctx: QuickJSContext,
        private val obj: JSObject,
        private val bag: MutableList<JSObject>,
        override val isWindow: Boolean,
    ) : BetterNcmSearch.Cursor {
        override val identity: Any = obj.getPointer()

        override fun ownKeys(): List<String> = names("__bncmOwnKeys")

        override fun chainKeys(): List<String> = names("__bncmForIn")

        override fun kind(key: String): BetterNcmSearch.Kind {
            val value = obj.getProperty(key)
            return try {
                when (value) {
                    null -> BetterNcmSearch.Kind.NULL
                    is JSFunction -> BetterNcmSearch.Kind.FUNCTION
                    is JSObject -> BetterNcmSearch.Kind.OBJECT
                    else -> BetterNcmSearch.Kind.OTHER
                }
            } finally {
                if (value is JSObject) value.release()
            }
        }

        override fun child(key: String): BetterNcmSearch.Cursor? {
            val value = obj.getProperty(key)
            if (value !is JSObject || value is JSFunction) {
                if (value is JSObject) value.release()
                return null
            }
            bag.add(value)
            return JsCursor(ctx, value, bag, false)
        }

        override fun source(key: String): String? {
            val value = obj.getProperty(key) ?: return null
            return try {
                when (value) {
                    is JSFunction -> functionText(value)
                    is JSObject -> "[object Object]"
                    else -> value.toString()
                }
            } finally {
                if (value is JSObject) value.release()
            }
        }

        override fun test(key: String, predicate: (Any?) -> Boolean): Boolean {
            val value = obj.getProperty(key)
            return try {
                predicate(value)
            } finally {
                if (value is JSObject) value.release()
            }
        }

        private fun names(fnName: String): List<String> {
            val global = ctx.getGlobalObject()
            val fn = global.getJSFunction(fnName) ?: return emptyList()
            val result = fn.call(obj)
            fn.release()
            return readNames(result).also {
                if (result is JSObject) result.release()
            }
        }

        private fun functionText(fn: JSFunction): String {
            val global = ctx.getGlobalObject()
            val source = global.getJSFunction("__bncmFnSource") ?: return ""
            val text = source.call(fn) as? String ?: ""
            source.release()
            return text
        }

        private fun readNames(value: Any?): List<String> {
            val array = value as? JSArray ?: return emptyList()
            val out = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val item = array.get(i)
                if (item is String) out.add(item)
            }
            return out
        }
    }

    companion object {
        private const val JOB_TURNS = 8
        private val SCRIPT = """
            globalThis["await"] = function(value) { return value; };
            function __bncmOwnKeys(o) {
              try { return Object.keys(o); } catch (e) { return []; }
            }
            function __bncmForIn(o) {
              var names = [];
              try { for (var k in o) names.push(k); } catch (e) {}
              return names;
            }
            function __bncmFnSource(f) {
              try { return String(f); } catch (e) { return ""; }
            }
            function __bncmPath(currentPath) {
              if (!currentPath || !currentPath.length) return "[\"window\"]";
              var out = [];
              for (var i = 0; i < currentPath.length; i++) out.push(String(currentPath[i]));
              return JSON.stringify(out);
            }
            function __bncmHits(root, json) {
              if (json == null) return [];
              var hits = JSON.parse(json);
              var out = [];
              for (var i = 0; i < hits.length; i++) {
                var hit = hits[i];
                var owner = root;
                for (var p = 1; p < hit.path.length; p++) owner = owner[hit.path[p]];
                out.push([owner[hit.key], owner, hit.path]);
              }
              return out;
            }
            var __bncmById = {};
            var __bncmObservers = [];
            function __bncmRecord(target, type) {
              for (var i = 0; i < __bncmObservers.length; i++) {
                var ob = __bncmObservers[i];
                if (!ob || ob.dead) continue;
                ob.records.push({ type: type, target: target, addedNodes: [], removedNodes: [] });
                if (!ob.scheduled) {
                  ob.scheduled = true;
                  (function(observer) {
                    setTimeout(function() {
                      observer.scheduled = false;
                      if (observer.dead) return;
                      var batch = observer.records;
                      observer.records = [];
                      if (batch.length && observer.callback) observer.callback(batch, observer);
                    }, 0);
                  })(ob);
                }
              }
            }
            function __bncmNode(id, tag) {
              var idValue = "";
              var html = "";
              var listeners = {};
              var node = {
                __bncmNode: id,
                nodeType: 1,
                tagName: String(tag || __zmusicBncm("dom.tag", id) || "div").toUpperCase(),
                style: {
                  setProperty: function(key, value) {
                    __zmusicBncm("dom.cssvar", String(key), String(value == null ? "" : value));
                  }
                },
                classList: {
                  add: function() {
                    for (var i = 0; i < arguments.length; i++) {
                      __zmusicBncm("dom.class", id, String(arguments[i]), 1);
                    }
                    __bncmRecord(node, "attributes");
                  },
                  remove: function() {
                    for (var i = 0; i < arguments.length; i++) {
                      __zmusicBncm("dom.class", id, String(arguments[i]), 0);
                    }
                    __bncmRecord(node, "attributes");
                  },
                  contains: function(name) {
                    return !!__zmusicBncm("dom.classHas", id, String(name));
                  },
                  toggle: function(name, force) {
                    var has = !!__zmusicBncm("dom.classHas", id, String(name));
                    var next = force === undefined ? !has : !!force;
                    __zmusicBncm("dom.class", id, String(name), next ? 1 : 0);
                    __bncmRecord(node, "attributes");
                    return next;
                  }
                },
                appendChild: function(child) {
                  if (child && child.__bncmNode) __zmusicBncm("dom.append", id, child.__bncmNode);
                  __bncmRecord(node, "childList");
                  return child;
                },
                querySelector: function(sel) {
                  var found = __zmusicBncm("dom.query", String(sel || ""), id);
                  return found ? __bncmNode(found) : null;
                },
                querySelectorAll: function(sel) {
                  var raw = __zmusicBncm("dom.queryAll", String(sel || ""), id);
                  var ids = [];
                  try { ids = JSON.parse(raw || "[]"); } catch (e) { ids = []; }
                  var out = [];
                  for (var i = 0; i < ids.length; i++) out.push(__bncmNode(ids[i]));
                  return out;
                },
                setAttribute: function(key, value) {
                  if (key === "id") { this.id = String(value); return; }
                  if (key === "class") { this.className = String(value); return; }
                  __zmusicBncm("dom.attr", id, String(key), String(value == null ? "" : value));
                  __bncmRecord(node, "attributes");
                },
                addEventListener: function(type, fn) {
                  var list = listeners[type] || (listeners[type] = []);
                  if (typeof fn === "function") list.push(fn);
                },
                removeEventListener: function(type, fn) {
                  var list = listeners[type] || [];
                  listeners[type] = list.filter(function(item) { return item !== fn; });
                },
                click: function() {
                  var list = (listeners.click || []).slice();
                  for (var i = 0; i < list.length; i++) {
                    try { list[i].call(node); } catch (e) {}
                  }
                },
                parentNode: null
              };
              var textValue = "";
              Object.defineProperty(node, "textContent", {
                get: function() { return textValue; },
                set: function(value) {
                  textValue = String(value == null ? "" : value);
                  __zmusicBncm("dom.text", id, textValue);
                }
              });
              Object.defineProperty(node, "id", {
                get: function() { return idValue; },
                set: function(value) {
                  idValue = String(value || "");
                  __zmusicBncm("dom.attr", id, "id", idValue);
                  __bncmById[idValue] = node;
                }
              });
              Object.defineProperty(node, "className", {
                get: function() {
                  var raw = __zmusicBncm("dom.classes", id);
                  try { return JSON.parse(raw || "[]").join(" "); } catch (e) { return ""; }
                },
                set: function(value) {
                  __zmusicBncm("dom.className", id, String(value || ""));
                  __bncmRecord(node, "attributes");
                }
              });
              Object.defineProperty(node, "innerHTML", {
                get: function() { return html; },
                set: function(value) {
                  html = String(value || "");
                  if (node.tagName === "STYLE") {
                    __zmusicBncm("dom.text", id, html);
                    __zmusicBncm("dom.style", idValue || "inline-style", html, "{}");
                  } else {
                    __zmusicBncm("dom.html", id, html);
                  }
                  __bncmRecord(node, "childList");
                }
              });
              return node;
            }
            var Node = { ELEMENT_NODE: 1, TEXT_NODE: 3, COMMENT_NODE: 8, DOCUMENT_NODE: 9 };
            function Event(type, init) {
              init = init || {};
              this.type = String(type || "");
              this.bubbles = !!init.bubbles;
              this.cancelable = !!init.cancelable;
              this.composed = !!init.composed;
              this.defaultPrevented = false;
              this.cancelBubble = false;
              this.target = null;
              this.currentTarget = null;
              this.timeStamp = Date.now();
            }
            Event.prototype.preventDefault = function() {
              if (this.cancelable) this.defaultPrevented = true;
            };
            Event.prototype.stopPropagation = function() { this.cancelBubble = true; };
            Event.prototype.stopImmediatePropagation = function() {
              this.cancelBubble = true;
              this.__bncmStopNow = true;
            };
            function CustomEvent(type, init) {
              Event.call(this, type, init || {});
              this.detail = init && init.detail !== undefined ? init.detail : null;
            }
            CustomEvent.prototype = Object.create(Event.prototype);
            CustomEvent.prototype.constructor = CustomEvent;
            function EventTarget() {
              this.__bncmEt = {};
            }
            EventTarget.prototype.addEventListener = function(type, listener, options) {
              if (typeof listener !== "function" || !type) return;
              var key = String(type);
              var list = this.__bncmEt[key] || (this.__bncmEt[key] = []);
              for (var i = 0; i < list.length; i++) if (list[i].fn === listener) return;
              list.push({ fn: listener, once: !!(options && options.once) });
            };
            EventTarget.prototype.removeEventListener = function(type, listener) {
              var list = this.__bncmEt[String(type)];
              if (!list) return;
              this.__bncmEt[String(type)] = list.filter(function(item) { return item.fn !== listener; });
            };
            EventTarget.prototype.dispatchEvent = function(event) {
              if (!event || !event.type) return false;
              if (!event.target) event.target = this;
              event.currentTarget = this;
              var list = (this.__bncmEt[String(event.type)] || []).slice();
              for (var i = 0; i < list.length; i++) {
                try { list[i].fn.call(this, event); } catch (e) {
                  if (typeof console !== "undefined" && console.error) console.error(e);
                }
                if (list[i].once) this.removeEventListener(event.type, list[i].fn);
                if (event.__bncmStopNow) break;
              }
              return !event.defaultPrevented;
            };
            function MutationObserver(callback) {
              this.callback = callback;
              this.records = [];
              this.dead = false;
              this.scheduled = false;
            }
            MutationObserver.prototype.observe = function() {
              if (__bncmObservers.indexOf(this) < 0) __bncmObservers.push(this);
            };
            MutationObserver.prototype.disconnect = function() {
              this.dead = true;
              var self = this;
              var next = [];
              for (var i = 0; i < __bncmObservers.length; i++) {
                if (__bncmObservers[i] !== self) next.push(__bncmObservers[i]);
              }
              __bncmObservers = next;
            };
            MutationObserver.prototype.takeRecords = function() {
              var batch = this.records;
              this.records = [];
              return batch;
            };
            var __bncmWindowTarget = new EventTarget();
            globalThis.addEventListener = function(type, listener, options) {
              __bncmWindowTarget.addEventListener(type, listener, options);
            };
            globalThis.removeEventListener = function(type, listener) {
              __bncmWindowTarget.removeEventListener(type, listener);
            };
            globalThis.dispatchEvent = function(event) {
              return __bncmWindowTarget.dispatchEvent(event);
            };
            function __bncmBlob(meta) {
              var text = "";
              try { text = String(__zmusicBncm("b64.text", meta.b64) || ""); } catch (e) { text = ""; }
              var blob = {
                size: meta.size,
                __bncmB64: meta.b64,
                text: function() { return text; },
                arrayBuffer: function() { return meta.b64; },
                then: function(ok, err) {
                  var self = this;
                  setTimeout(function() {
                    try {
                      if (typeof ok === "function") ok(self);
                    } catch (e) {
                      if (typeof err === "function") err(e);
                    }
                  }, 0);
                  return this;
                }
              };
              return blob;
            }
            var __bncmTimers = {};
            function setTimeout(fn, ms) {
              var id = __zmusicBncm("timer.arm", ms, 0);
              __bncmTimers[id] = { fn: fn, interval: false };
              return id;
            }
            function setInterval(fn, ms) {
              var id = __zmusicBncm("timer.arm", ms, 1);
              __bncmTimers[id] = { fn: fn, interval: true };
              return id;
            }
            function __bncmClear(id) {
              delete __bncmTimers[id];
              __zmusicBncm("timer.clear", id);
            }
            function clearTimeout(id) { __bncmClear(id); }
            function clearInterval(id) { __bncmClear(id); }
            var __bncmWatches = {};
            var __bncmWatchSeq = 0;
            function __bncmWatchFire(id, dir, names) {
              var fn = __bncmWatches[id];
              if (typeof fn !== "function") return;
              var list = names || [];
              for (var i = 0; i < list.length; i++) {
                try { fn(String(dir), String(list[i])); } catch (e) {}
              }
            }
            function __bncmFire(id) {
              var slot = __bncmTimers[id];
              if (!slot) return;
              if (!slot.interval) delete __bncmTimers[id];
              try { slot.fn(); } catch (e) { console.error(e); }
            }
            var __bncmOnLoad = [];
            var __bncmOnAll = [];
            var __bncmOnConfig = [];
            var loadedPlugins = {};
            globalThis.window = globalThis;
            var plugin = {
              pluginPath: "",
              manifest: {},
              slug: "",
              filePath: "",
              finished: false,
              loadError: null,
              onLoad: function(fn) { __bncmOnLoad.push(fn); },
              onAllPluginsLoaded: function(fn) { __bncmOnAll.push(fn); },
              onConfig: function(fn) { __bncmOnConfig.push(fn); },
              getConfig: function(key) {
                var encoded = __zmusicBncm("cfg.get", plugin.slug, String(key));
                if (encoded == null) return arguments.length >= 2 ? arguments[1] : undefined;
                return JSON.parse(encoded);
              },
              setConfig: function(key, value) {
                var encoded = JSON.stringify(value);
                if (encoded === undefined) encoded = "null";
                var ok = __zmusicBncm("cfg.set", plugin.slug, String(key), encoded);
                if (!ok) throw new Error("config");
              }
            };
            function __bncmBind(path, manifestJson, slug) {
              plugin.pluginPath = path;
              plugin.slug = slug;
              plugin.manifest = JSON.parse(manifestJson);
              loadedPlugins[slug] = {
                manifest: plugin.manifest,
                pluginPath: path,
                slug: slug,
                injects: [plugin]
              };
              plugin.mainPlugin = loadedPlugins[slug];
              loadedPlugins[slug].mainPlugin = loadedPlugins[slug];
            }
            function __bncmDispatch(kind) {
              var list = kind === "allpluginsloaded" ? __bncmOnAll : __bncmOnLoad;
              var detail = kind === "allpluginsloaded" ? loadedPlugins : plugin;
              var evt = { detail: detail, type: kind };
              for (var i = 0; i < list.length; i++) {
                try {
                  list[i].call(plugin, detail, evt);
                } catch (e) {
                  plugin.loadError = (e && (e.stack || e.message)) ? (e.stack || e.message) : String(e);
                  return;
                }
              }
              if (kind === "load") {
                plugin.finished = true;
                if (plugin.mainPlugin) plugin.mainPlugin.finished = true;
              }
            }
            function __bncmLoadError() {
              return plugin.loadError ? String(plugin.loadError) : "";
            }
            var __bncmStorage = {
              getItem: function(key) {
                var value = __zmusicBncm("ls.get", String(key));
                return value == null ? null : String(value);
              },
              setItem: function(key, value) { __zmusicBncm("ls.set", String(key), String(value)); },
              removeItem: function(key) { __zmusicBncm("ls.remove", String(key)); },
              clear: function() { __zmusicBncm("ls.clear"); },
              key: function(index) {
                var keys = [];
                try { keys = JSON.parse(__zmusicBncm("ls.keys") || "[]"); } catch (e) { keys = []; }
                return keys[index] == null ? null : String(keys[index]);
              }
            };
            var localStorage = new Proxy(__bncmStorage, {
              get: function(target, key) {
                if (key === "length") {
                  var keys = [];
                  try { keys = JSON.parse(__zmusicBncm("ls.keys") || "[]"); } catch (e) { keys = []; }
                  return keys.length;
                }
                if (key in target) return target[key];
                return target.getItem(String(key));
              },
              set: function(target, key, value) {
                target.setItem(String(key), String(value));
                return true;
              }
            });
            var channel = {
              call: function(name, cb, args) {
                var raw = __zmusicBncm("channel", String(name || ""), JSON.stringify(args || []));
                var result = null;
                if (raw != null && raw !== "") {
                  try { result = JSON.parse(String(raw)); } catch (e) { result = raw; }
                }
                if (typeof cb === "function") { try { cb(result); } catch (e2) {} }
                return Promise.resolve(result);
              },
              registerCall: function() {},
              encryptId: function(id) { return String(id); }
            };
            function fetch(url, options) {
              options = options || {};
              var method = options.method ? String(options.method) : "GET";
              var headers = options.headers || {};
              var body = options.body == null ? "" : String(options.body);
              var raw = __zmusicBncm("fetch", method, String(url || ""), body, JSON.stringify(headers));
              var meta = { status: 0, text: "", b64: "" };
              try { meta = JSON.parse(raw); } catch (e) {}
              var response = {
                ok: meta.status >= 200 && meta.status < 300,
                status: meta.status || 0,
                text: function() { return Promise.resolve(meta.text || ""); },
                json: function() { return Promise.resolve(JSON.parse(meta.text || "null")); },
                blob: function() {
                  return Promise.resolve({
                    size: meta.b64 ? meta.b64.length : 0,
                    __bncmB64: meta.b64 || "",
                    text: function() { return Promise.resolve(meta.text || ""); }
                  });
                }
              };
              return Promise.resolve(response);
            }
            var betterncm_native = {
              fs: {
                readDir: function(path) {
                  var raw = __zmusicBncm("fs.readDir", String(path));
                  if (raw == null) throw new Error("readDir");
                  return JSON.parse(raw);
                },
                readFileText: function(path) {
                  var text = __zmusicBncm("fs.readFileText", String(path));
                  if (text == null) throw new Error("readFileText");
                  return text;
                },
                unzip: function(path, dest) { return __zmusicBncm("fs.unzip", String(path), dest == null ? "" : String(dest)); },
                exists: function(path) { return !!__zmusicBncm("fs.exists", String(path)); },
                writeFileText: function(path, body) { return !!__zmusicBncm("fs.writeFileText", String(path), String(body)); },
                remove: function(path) { return !!__zmusicBncm("fs.remove", String(path)); },
                rename: function(path, dest) { return !!__zmusicBncm("fs.rename", String(path), String(dest)); },
                mountFile: function(path) { return String(__zmusicBncm("fs.mount", String(path)) || ""); },
                mountDir: function(path) { return String(__zmusicBncm("fs.mount", String(path)) || ""); },
                watchDirectory: function(path, callback) {
                  var id = ++__bncmWatchSeq;
                  __bncmWatches[id] = callback;
                  var ok = __zmusicBncm("fs.watch", String(path), id);
                  if (!ok) delete __bncmWatches[id];
                }
              },
              app: {
                datapath: function() { return __zmusicBncm("app.datapath"); },
                version: function() { return __zmusicBncm("app.version"); },
                ncmpath: function() { return ""; },
                reloadIgnoreCache: function() { return !!__zmusicBncm("app.reload"); },
                restart: function() { return !!__zmusicBncm("app.reload"); },
                crash: function() { return false; }
              },
              native_plugin: {
                getRegisteredAPIs: function() { return []; },
                call: function() { return false; }
              }
            };
            var betterncm = {
              fs: {
                readDir: function(path) { return Promise.resolve(betterncm_native.fs.readDir(path)); },
                readFileText: function(path) { return Promise.resolve(betterncm_native.fs.readFileText(path)); },
                readFile: function(path) {
                  var raw = __zmusicBncm("fs.readFile", String(path));
                  if (raw == null) throw new Error("readFile");
                  return __bncmBlob(JSON.parse(raw));
                },
                writeFileText: function(path, content) {
                  return Promise.resolve(betterncm_native.fs.writeFileText(path, content));
                },
                writeFile: function(path, content) {
                  var b64 = content && content.__bncmB64;
                  if (!b64) b64 = __zmusicBncm("utf8.b64", String(content));
                  return Promise.resolve(!!__zmusicBncm("fs.writeFile", String(path), b64));
                },
                mkdir: function(path) { return Promise.resolve(!!__zmusicBncm("fs.mkdir", String(path))); },
                exists: function(path) { return Promise.resolve(!!__zmusicBncm("fs.exists", String(path))); },
                remove: function(path) { return Promise.resolve(!!__zmusicBncm("fs.remove", String(path))); },
                unzip: function(path, dest) {
                  var target = dest == null ? (String(path) + "_extracted/") : String(dest);
                  var code = __zmusicBncm("fs.unzip", String(path), target);
                  return Promise.resolve(parseInt(code, 10) === 0);
                },
                mountFile: function(path) { return Promise.resolve(betterncm_native.fs.mountFile(path)); },
                mountDir: function(path) { return Promise.resolve(betterncm_native.fs.mountDir(path)); }
              },
              app: {
                getBetterNCMVersion: function() { return Promise.resolve(__zmusicBncm("app.version")); },
                getDataPath: function() {
                  return Promise.resolve(String(__zmusicBncm("app.datapath")).replace(/\//g, "\\"));
                },
                readConfig: function(key, defaultValue) {
                  return Promise.resolve(__zmusicBncm("app.readConfig", String(key), defaultValue == null ? "" : String(defaultValue)));
                },
                writeConfig: function(key, value) {
                  return Promise.resolve(!!__zmusicBncm("app.writeConfig", String(key), String(value)));
                },
                isLightTheme: function() { return Promise.resolve(!!__zmusicBncm("app.light")); },
                exec: function(cmd) {
                  var text = String(cmd || "");
                  if (/^https?:\/\//i.test(text)) {
                    __zmusicBncm("ncm.openUrl", text);
                    return Promise.resolve(true);
                  }
                  return Promise.resolve(!!__zmusicBncm("app.exec", text));
                },
                takeBackgroundScreenshot: function() {
                  var raw = __zmusicBncm("app.shot");
                  return Promise.resolve(__bncmBlob(JSON.parse(raw)));
                },
                getNCMWinPos: function() { return Promise.resolve(JSON.parse(__zmusicBncm("app.win"))); },
                reloadPlugins: function() { return Promise.resolve(true); },
                getNCMPath: function() { return Promise.resolve(""); },
                showConsole: function() { return Promise.resolve(false); },
                setRoundedCorner: function() { return Promise.resolve(true); },
                openFileDialog: function(filter, initialDir) {
                  var picked = __zmusicBncm("app.openFile", filter == null ? "" : String(filter), initialDir == null ? "" : String(initialDir));
                  return Promise.resolve(picked == null ? "" : String(picked));
                },
                getSucceededHijacks: function() { return Promise.resolve([]); }
              },
              ncm: {
                findNativeFunction: function(obj, identifiers) {
                  var key = __zmusicBncm("search.native", obj, String(identifiers));
                  return key == null ? undefined : key;
                },
                getNCMPackageVersion: function() { return __zmusicBncm("ncm.package"); },
                getNCMFullVersion: function() { return __zmusicBncm("ncm.full"); },
                getNCMVersion: function() { return __zmusicBncm("ncm.version"); },
                getNCMBuild: function() {
                  var n = __zmusicBncm("ncm.build");
                  return n == null ? NaN : n;
                },
                searchApiFunction: function(nameOrFinder, root, currentPath) {
                  var base = root === undefined || root === null ? globalThis : root;
                  var path = __bncmPath(currentPath);
                  var json = (typeof nameOrFinder === "string")
                    ? __zmusicBncm("search.allName", base, nameOrFinder, path)
                    : __zmusicBncm("search.allPred", base, nameOrFinder, path);
                  return __bncmHits(base, json);
                },
                searchForData: function(finder, root, currentPath) {
                  var base = root === undefined || root === null ? globalThis : root;
                  var json = __zmusicBncm("search.data", base, finder, __bncmPath(currentPath));
                  return __bncmHits(base, json);
                },
                findApiFunction: function(nameOrFinder, root, currentPath) {
                  var base = root === undefined || root === null ? globalThis : root;
                  var path = __bncmPath(currentPath);
                  var json = (typeof nameOrFinder === "string")
                    ? __zmusicBncm("search.oneName", base, nameOrFinder, path)
                    : __zmusicBncm("search.onePred", base, nameOrFinder, path);
                  var hits = __bncmHits(base, json);
                  return hits.length ? hits[0] : null;
                },
                getPlayingSong: function() {
                  var raw = __zmusicBncm("ncm.playing");
                  if (raw == null) return null;
                  return JSON.parse(raw);
                },
                getPlaying: function() {
                  var playing = betterncm.ncm.getPlayingSong();
                  var result = { id: playing.data.id, title: playing.data.name, type: "normal" };
                  if (playing.from.fm) result.type = "fm";
                  return result;
                },
                openUrl: function(url) { __zmusicBncm("ncm.openUrl", String(url)); },
                eapiRequest: function(url, options) {
                  options = options || {};
                  var payload = JSON.stringify({
                    url: String(url || ""),
                    query: options.query || {},
                    data: options.data || {}
                  });
                  var raw = __zmusicBncm("eapi", payload);
                  var body = {};
                  try { body = JSON.parse(raw); } catch (e) { body = { code: 500 }; }
                  var failed = body && typeof body.code === "number" && body.code !== 200 && body.code !== 201;
                  if (failed && options.onerror) options.onerror(body);
                  else if (options.onload) options.onload(body);
                  return body;
                }
              },
              utils: {
                debounce: function(callback, waitTime) {
                  var timer = 0;
                  return function() {
                    var self = this;
                    var args = arguments;
                    if (timer) clearTimeout(timer);
                    timer = setTimeout(function() {
                      timer = 0;
                      callback.call(self, args);
                    }, waitTime);
                  };
                },
                waitForFunction: function(func, interval) {
                  var step = interval === undefined ? 100 : interval;
                  return new Promise(function(resolve) {
                    var id = setInterval(function() {
                      var result = func();
                      if (result) {
                        clearInterval(id);
                        resolve(result);
                      }
                    }, step);
                  });
                },
                delay: function(ms) {
                  return new Promise(function(resolve) { setTimeout(resolve, ms); });
                },
                waitForElement: function(selector, interval) {
                  return betterncm.utils.waitForFunction(function() {
                    var id = __zmusicBncm("dom.query", String(selector));
                    return id ? __bncmNode(id) : null;
                  }, interval);
                },
                dom: function(tag, settings) {
                  var childIds = [];
                  for (var i = 2; i < arguments.length; i++) {
                    if (arguments[i] && arguments[i].__bncmNode) childIds.push(arguments[i].__bncmNode);
                  }
                  var id = __zmusicBncm("dom.create", String(tag), JSON.stringify(settings || {}), JSON.stringify(childIds));
                  if (!id) return null;
                  return __bncmNode(id);
                }
              },
              tests: {
                fail: function(reason) {
                  console.warn("Test Failed", reason);
                  return betterncm.fs.writeFileText("/__TEST_FAILED__.txt", String(reason));
                },
                success: function(message) {
                  console.warn("Test Succeeded", message);
                  return betterncm.fs.writeFileText("/__TEST_SUCCEEDED__.txt", String(message));
                }
              },
              betterncmFetch: function(relPath, option) {
                var method = option && option.method ? String(option.method) : "GET";
                var body = option && typeof option.body === "string" ? option.body : "";
                var packed = JSON.parse(__zmusicBncm("http", method, String(relPath), body));
                var response = {
                  status: packed.status,
                  text: function() { return Promise.resolve(packed.text); },
                  json: function() { return Promise.resolve(JSON.parse(packed.text)); },
                  blob: function() { return Promise.resolve(packed.b64 || packed.text); }
                };
                return Promise.resolve(response);
              },
              reload: function() { __zmusicBncm("app.reload"); }
            };
            function __bncmTools() {
              return {
                makeBtn: function(text, onClick, smaller) {
                  var classes = ["u-ibtn5"];
                  if (smaller) classes.push("u-ibtnsz8");
                  var el = betterncm.utils.dom("a", { class: classes, innerText: text });
                  if (el && typeof onClick === "function") el.addEventListener("click", onClick);
                  return el;
                },
                makeCheckbox: function(args) {
                  return betterncm.utils.dom("input", Object.assign({ type: "checkbox" }, args || {}));
                },
                makeInput: function(value, args) {
                  return betterncm.utils.dom("input", Object.assign({ value: value, class: ["u-txt", "sc-flag"] }, args || {}));
                }
              };
            }
            function __bncmConfig() {
              var tools = __bncmTools();
              var el = null;
              for (var i = 0; i < __bncmOnConfig.length; i++) {
                try {
                  el = __bncmOnConfig[i].call(plugin, tools) || el;
                } catch (e) {
                  plugin.loadError = (e && (e.stack || e.message)) ? (e.stack || e.message) : String(e);
                }
              }
              return el;
            }
            var APP_CONF = {
              domain: "https://interface.music.163.com",
              get appver() { return __zmusicBncm("ncm.full"); },
              get packageVersion() { return __zmusicBncm("ncm.package"); }
            };
        """.trimIndent()

        internal fun scriptSource(): String = SCRIPT
    }
}
