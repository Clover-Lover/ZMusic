package com.kite.zmusic.plugin

import java.io.File
import java.nio.file.Files
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BetterNcmPathsTest {
    @Test
    fun presentDataPathUsesBackslash() {
        assertEquals("C:\\", BetterNcmPaths.presentDataPath())
    }

    @Test
    fun relativeAndSlashFormsLandInDataDrive() {
        val (data, plugin) = roots()
        val host = host(data, plugin)
        assertTrue(host.mkdir("notes"))
        assertTrue(host.writeFileText("notes/a.txt", "hi"))
        assertEquals("hi", host.readFileText("notes\\a.txt"))
        assertEquals("hi", host.readFileText("/notes/a.txt"))
        assertEquals("hi", host.readFileText("C:/notes/a.txt"))
        assertEquals("hi", host.readFileText("c:\\notes\\a.txt"))
        assertTrue(File(data, "C/notes/a.txt").isFile)
    }

    @Test
    fun otherDriveIsAFolderInsideTheSandbox() {
        val (data, plugin) = roots()
        val host = host(data, plugin)
        assertTrue(host.mkdir("D:\\box"))
        assertTrue(host.writeFileText("D:\\box\\a.txt", "d"))
        assertTrue(File(data, "D/box/a.txt").isFile)
        assertFalse(host.writeFileText("D:\\..\\..\\outside.txt", "no"))
        assertFalse(File(data.parentFile, "outside.txt").exists())
    }

    @Test
    fun parentEscapeIsRejected() {
        val (data, plugin) = roots()
        val host = host(data, plugin)
        assertFalse(host.writeFileText("C:\\..\\..\\outside.txt", "no"))
    }

    @Test
    fun pluginTreeIsThePackageRoot() {
        val (data, plugin) = roots()
        val host = host(data, plugin)
        File(plugin, "main.js").writeText("plugin", Charsets.UTF_8)
        val path = "C:\\plugins_runtime\\main.js"
        assertEquals("plugin", host.readFileText(path))
        assertEquals("plugin", host.readFileText("plugins_runtime/main.js"))
        assertFalse(host.exists("D:\\plugins_runtime\\main.js"))
    }

    @Test
    fun pluginsMountIsVisibleFromDataRoot() {
        val (data, plugin) = roots()
        val host = host(data, plugin)
        File(plugin, "main.js").writeText("plugin", Charsets.UTF_8)
        assertTrue(host.exists("C:\\plugins_runtime"))
        assertEquals(listOf("C:\\plugins_runtime\\main.js"), host.readDir("C:\\plugins_runtime"))
        val listed = host.readDir("C:\\")!!.toSet()
        assertTrue(listed.contains("C:\\plugins_runtime"))
    }

    private fun roots(): Pair<File, File> {
        val root = Files.createTempDirectory("bncm-path").toFile()
        return File(root, "data") to File(root, "plugin")
    }

    private fun host(data: File, plugin: File) = BetterNcmHost(data, "com.example.demo", plugin)
}

class BetterNcmFsTest {
    @Test
    fun textRoundTripAndMissingFileIsEmpty() {
        val host = newHost()
        assertTrue(host.writeFileText("C:\\a.txt", "你好"))
        assertEquals("你好", host.readFileText("a.txt"))
        assertEquals("", host.readFileText("missing.txt"))
        assertTrue(host.exists("a.txt"))
        assertFalse(host.exists("missing.txt"))
    }

    @Test
    fun writeRequiresExistingParent() {
        val host = newHost()
        assertFalse(host.writeFileText("nope/a.txt", "x"))
        assertTrue(host.mkdir("nope/nested"))
        assertTrue(host.writeFileText("nope/nested/a.txt", "x"))
        assertTrue(host.exists("nope/nested"))
    }

    @Test
    fun readDirOfMissingOrFileFails() {
        val host = newHost()
        host.writeFileText("a.txt", "x")
        assertNull(host.readDir("missing"))
        assertNull(host.readDir("a.txt"))
        assertEquals(setOf("C:\\a.txt", "C:\\plugins_runtime"), host.readDir("C:\\")!!.toSet())
    }

    @Test
    fun removeDeletesTreeAndMissingIsSuccess() {
        val host = newHost()
        host.mkdir("box")
        host.writeFileText("box/a.txt", "x")
        assertTrue(host.remove("box"))
        assertFalse(host.exists("box"))
        assertTrue(host.remove("box"))
        assertFalse(host.remove("C:\\"))
        assertFalse(host.remove("C:\\plugins_runtime"))
    }

    @Test
    fun binaryRoundTripAndRename() {
        val host = newHost()
        val bytes = byteArrayOf(0, 1, 2, 255.toByte())
        assertTrue(host.writeFile("bin.dat", bytes))
        assertTrue(host.readFile("bin.dat")!!.contentEquals(bytes))
        assertTrue(host.rename("bin.dat", "moved.dat"))
        assertFalse(host.exists("bin.dat"))
        assertTrue(host.readFile("moved.dat")!!.contentEquals(bytes))
        assertFalse(host.rename("moved.dat", "C:\\moved.dat"))
    }

    @Test
    fun unzipExtractsAndRejectsSlip() {
        val host = newHost()
        val zip = File(host.dataRoot, "C/pack.zip")
        zip(zip, "hello.txt" to "world")
        assertEquals(0, host.unzip("pack.zip", null))
        assertEquals("world", host.readFileText("pack.zip_extracted/hello.txt"))

        val evil = File(host.dataRoot.parentFile, "evil.txt")
        val slip = File(host.dataRoot, "C/slip.zip")
        zip(slip, "../../evil.txt" to "no", "ok.txt" to "yes")
        assertEquals(-1, host.unzip("slip.zip", "slipped"))
        assertFalse(evil.exists())
        assertFalse(host.exists("slipped/ok.txt"))
        assertEquals(-1, host.unzip("missing.zip", "out"))
    }

    @Test
    fun configPersistsStringsAndDefaults() {
        val dir = Files.createTempDirectory("bncm-cfg").toFile()
        val plugin = File(dir, "plugin")
        val data = File(dir, "data")
        val first = BetterNcmHost(data, "com.example.demo", plugin)
        assertEquals("fallback", first.readConfig("missing", "fallback"))
        assertTrue(first.writeConfig("theme", "dark"))
        val second = BetterNcmHost(data, "com.example.demo", plugin)
        assertEquals("dark", second.readConfig("theme", "fallback"))
    }

    @Test
    fun localStorageAndPluginConfig() {
        val host = newHost()
        assertNull(host.localGet("a"))
        assertTrue(host.localSet("a", "1"))
        assertEquals("1", host.localGet("a"))
        assertTrue(host.localRemove("a"))
        assertNull(host.localGet("a"))

        assertTrue(host.configGet("slug", "k") === BetterNcmHost.Missing)
        assertTrue(host.configSet("slug", "k", "v"))
        assertEquals("v", host.configGet("slug", "k"))
        assertTrue(host.configSet("slug", "n", 3))
        assertEquals(3, host.configGet("slug", "n"))
        assertTrue(host.configSet("slug", "flag", false))
        assertEquals(false, host.configGet("slug", "flag"))
        host.localSet(BetterNcmHost.configKey("slug"), "{")
        assertFalse(host.configSet("slug", "k", "x"))
        host.localSet(BetterNcmHost.configKey("slug"), "null")
        assertTrue(host.configSet("slug", "k", "again"))
        assertEquals("again", host.configGet("slug", "k"))
        host.localSet(BetterNcmHost.configKey("slug"), "[]")
        assertTrue(host.configSet("slug", "k", "from-array"))
        assertEquals("from-array", host.configGet("slug", "k"))
    }

    private fun newHost(): BetterNcmHost {
        val root = Files.createTempDirectory("bncm-fs").toFile()
        return BetterNcmHost(File(root, "data"), "com.example.demo", File(root, "plugin"))
    }

    private fun zip(file: File, vararg entries: Pair<String, String>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
    }
}

class BetterNcmHttpTest {
    @Test
    fun routesMatchPublicStatusAndBody() {
        val host = host()
        assertEquals(200, host.http("GET", "/app/version").status)
        assertEquals(BetterNcmHost.API_VERSION, host.http("GET", "/app/version").text)
        assertEquals("C:/", host.http("GET", "/app/datapath").text)
        assertEquals("true", host.http("GET", "/app/is_light_theme").text)

        assertEquals(200, host.http("GET", "/app/read_config?key=missing&default=no").status)
        assertEquals("no", host.http("GET", "/app/read_config?key=missing&default=no").text)
        assertEquals(200, host.http("GET", "/app/write_config?key=theme&value=dark").status)
        assertEquals("dark", host.http("GET", "/app/read_config?key=theme&default=").text)

        assertEquals(200, host.http("POST", "/fs/write_file_text?path=a.txt", "hi").status)
        assertEquals("hi", host.http("GET", "/fs/read_file_text?path=a.txt").text)
        assertEquals("true", host.http("GET", "/fs/exists?path=a.txt").text)
        assertEquals("false", host.http("GET", "/fs/exists?path=nope").text)

        val listed = PluginJson.parse(host.http("GET", "/fs/read_dir?path=C:\\").text) as List<*>
        assertTrue(listed.contains("C:\\a.txt"))

        assertEquals(500, host.http("GET", "/fs/read_dir?path=missing").status)
        assertEquals(500, host.http("POST", "/fs/write_file_text?path=missing-parent/a.txt", "x").status)
        assertEquals(404, host.http("POST", "/fs/read_dir?path=C:\\").status)
        assertEquals(404, host.http("GET", "/app/exec").status)

        assertEquals(200, host.http("GET", "/fs/mkdir?path=dir").status)
        assertEquals(200, host.http("GET", "/fs/remove?path=a.txt").status)
        assertEquals("false", host.http("GET", "/fs/exists?path=a.txt").text)
        assertEquals(200, host.http("GET", "/fs/remove?path=gone").status)
        val posted = "bytes".toByteArray(Charsets.UTF_8)
        assertEquals(200, host.http("POST", "/fs/write_file?path=raw.bin", bytes = posted).status)
        assertTrue(host.readFile("raw.bin")!!.contentEquals(posted))
    }

    @Test
    fun encodedPathAndUnzipCode() {
        val host = host()
        host.writeFileText("my file.txt", "ok")
        assertEquals("ok", host.http("GET", "/fs/read_file_text?path=my%20file.txt").text)
        val zip = File(host.dataRoot, "C/p.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("n.txt"))
            out.write("z".toByteArray())
            out.closeEntry()
        }
        val reply = host.http("GET", "/fs/unzip_file?path=p.zip")
        assertEquals(200, reply.status)
        assertEquals("0", reply.text)
        assertEquals("z", host.readFileText("p.zip_extracted/n.txt"))
        val bytes = host.http("GET", "/fs/read_file?path=p.zip_extracted/n.txt")
        assertTrue(bytes.bytes!!.contentEquals("z".toByteArray()))
    }

    @Test
    fun lightThemeFollowsHost() {
        var light = false
        val root = Files.createTempDirectory("bncm-theme").toFile()
        val host = BetterNcmHost(File(root, "data"), "com.example.demo", File(root, "plugin"), lightTheme = { light })
        assertEquals("false", host.http("GET", "/app/is_light_theme").text)
        light = true
        assertEquals("true", host.http("GET", "/app/is_light_theme").text)
    }

    private fun host(): BetterNcmHost {
        val root = Files.createTempDirectory("bncm-http").toFile()
        return BetterNcmHost(File(root, "data"), "com.example.demo", File(root, "plugin"))
    }
}

class BetterNcmVersionsTest {
    @Test
    fun fallbacksWhenUnset() {
        assertEquals("0000000", BetterNcmVersions.packageVersion(null))
        assertEquals("0000000", BetterNcmVersions.packageVersion(""))
        assertEquals("0.0.0.0", BetterNcmVersions.fullVersion(null))
        assertEquals("2.10.12", BetterNcmVersions.packageVersion("2.10.12"))
    }

    @Test
    fun versionDropsBuildAndBuildParsesLeadingInt() {
        assertEquals("2.10.12", BetterNcmVersions.version("2.10.12.200500"))
        assertEquals(200500, BetterNcmVersions.build("2.10.12.200500"))
        assertEquals(10, BetterNcmVersions.build("1.2.10abc"))
        assertNull(BetterNcmVersions.build("1.2.x"))
        assertNull(BetterNcmVersions.jsParseInt(""))
        try {
            BetterNcmVersions.version("noperiod")
            throw AssertionError("expected throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun hostUsesFallbacksUntilOverridden() {
        val root = Files.createTempDirectory("bncm-ver").toFile()
        var appver: String? = null
        var pkg: String? = null
        val host = BetterNcmHost(
            File(root, "data"),
            "com.example.demo",
            File(root, "plugin"),
            appver = { appver },
            packageVersion = { pkg },
        )
        assertEquals("0.0.0.0", host.ncmFullVersion())
        assertEquals("0.0.0", host.ncmVersion())
        assertEquals(0, host.ncmBuild())
        assertEquals("0000000", host.ncmPackageVersion())
        appver = "3.0.1.42"
        pkg = "3001000"
        assertEquals("3.0.1", host.ncmVersion())
        assertEquals(42, host.ncmBuild())
        assertEquals("3001000", host.ncmPackageVersion())
    }
}

class BetterNcmPlayingTest {
    @Test
    fun noTrackIsNull() {
        assertNull(BetterNcmPlaying.song(PluginPlaybackSnapshot.EMPTY))
    }

    @Test
    fun songShapeAndBrief() {
        val snap = PluginPlaybackSnapshot(
            playing = true,
            positionMs = 15,
            track = PluginTrackSnap(9, "歌", "甲 / 乙", "专辑", 1000, "https://cover"),
        )
        val song = BetterNcmPlaying.song(snap)!!
        val data = song["data"] as Map<*, *>
        assertEquals(9L, data["id"])
        assertEquals("歌", data["name"])
        val artists = data["artists"] as List<*>
        assertEquals(listOf("甲", "乙"), artists.map { (it as Map<*, *>)["name"] })
        val album = data["album"] as Map<*, *>
        assertEquals("专辑", album["name"])
        assertEquals("https://cover", album["picUrl"])
        assertEquals(1000L, data["duration"])
        val from = song["from"] as Map<*, *>
        assertEquals(false, from["fm"])
        assertEquals(true, song["playing"])
        assertEquals(1, song["state"])
        assertEquals(15L, song["positionMs"])
        assertEquals(
            linkedMapOf("id" to 9L, "title" to "歌", "type" to "normal"),
            BetterNcmPlaying.brief(song),
        )
        val fm = LinkedHashMap(song)
        fm["from"] = linkedMapOf("fm" to true)
        assertEquals("fm", BetterNcmPlaying.brief(fm)["type"])
    }
}

class BetterNcmSearchTest {
    @Test
    fun findsNamedFunctionAndSkipsWindowBetterncm() {
        val inner = MemCursor(listOf("play" to MemProp.Fn("secret")))
        val nested = MemCursor(listOf("play" to MemProp.Fn("visible")))
        val root = MemCursor(
            listOf(
                "betterncm" to MemProp.Obj(inner),
                "other" to MemProp.Obj(MemCursor(listOf("betterncm" to MemProp.Obj(nested)))),
            ),
            isWindow = true,
        )
        val hit = BetterNcmSearch.findApiFunction(root, "play", null)!!
        assertEquals(listOf("window", "other", "betterncm"), hit.path)
        assertEquals("play", hit.key)
        assertEquals("visible", hit.source)
    }

    @Test
    fun inheritedFunctionIsFoundByNameButNotByWalk() {
        val proto = MemCursor(listOf("hidden" to MemProp.Fn("proto")))
        val root = MemCursor(listOf("own" to MemProp.Leaf("x")), isWindow = true, proto = proto)
        val hit = BetterNcmSearch.findApiFunction(root, "hidden", null)!!
        assertEquals(listOf("window"), hit.path)
        val boxed = MemCursor(listOf("deep" to MemProp.Fn("no")))
        val protoBox = MemCursor(listOf("box" to MemProp.Obj(boxed)))
        val onlyProto = MemCursor(emptyList(), isWindow = true, proto = protoBox)
        assertNull(BetterNcmSearch.findApiFunction(onlyProto, "deep", null))
    }

    @Test
    fun depthStopsAtTenAndCycleEnds() {
        assertEquals("body", BetterNcmSearch.findApiFunction(wrapped(9), "fn", null)!!.source)
        assertNull(BetterNcmSearch.findApiFunction(wrapped(10), "fn", null))

        val props = ArrayList<Pair<String, MemProp>>()
        val loop = MemCursor(props, isWindow = true)
        val child = MemCursor(listOf("back" to MemProp.Obj(loop), "fn" to MemProp.Fn("here")))
        props.add("next" to MemProp.Obj(child))
        val hit = BetterNcmSearch.findApiFunction(loop, "fn", null)!!
        assertEquals("here", hit.source)
        assertNull(BetterNcmSearch.findApiFunction(loop, "missing", null))
    }

    @Test
    fun searchCollectsEveryMatchAndPredicateStopsEarly() {
        val root = MemCursor(
            listOf(
                "a" to MemProp.Fn("one"),
                "b" to MemProp.Obj(MemCursor(listOf("a" to MemProp.Fn("two")))),
            ),
            isWindow = true,
        )
        val all = BetterNcmSearch.searchApiFunction(root, "a", null)
        assertEquals(listOf("one", "two"), all.map { it.source })
        val first = BetterNcmSearch.findApiFunction(root, null, { it == "two" || it == "one" })!!
        assertEquals("one", first.source)
    }

    @Test
    fun searchForDataUsesFunctionSearchOnChildren() {
        val nested = MemCursor(listOf("fn" to MemProp.Fn("FUNC"), "leaf" to MemProp.Leaf("DATA")))
        val root = MemCursor(
            listOf("child" to MemProp.Obj(nested), "top" to MemProp.Leaf("TOP")),
            isWindow = true,
        )
        val hits = BetterNcmSearch.searchForData(
            root,
            { value -> value == "TOP" || value == "DATA" || value == "FUNC" },
        )
        assertEquals(setOf("top", "fn"), hits.map { it.key }.toSet())
    }

    @Test
    fun findNativeFunctionMatchesEveryCharacter() {
        val root = MemCursor(
            listOf(
                "no" to MemProp.Fn("abc"),
                "yes" to MemProp.Fn("abcXYZ"),
            ),
        )
        assertEquals("yes", BetterNcmSearch.findNativeFunction(root, "XYZ"))
        assertEquals("no", BetterNcmSearch.findNativeFunction(root, ""))
        val withNull = MemCursor(listOf("bad" to MemProp.Null))
        try {
            BetterNcmSearch.findNativeFunction(withNull, "a")
            throw AssertionError("expected throw")
        } catch (_: IllegalStateException) {
        }
    }

    private fun wrapped(edges: Int): MemCursor {
        var node = MemCursor(listOf("fn" to MemProp.Fn("body")))
        repeat(edges - 1) { node = MemCursor(listOf("c" to MemProp.Obj(node))) }
        return MemCursor(listOf("c" to MemProp.Obj(node)), isWindow = true)
    }
}

class BetterNcmLoadOrderTest {
    @Test
    fun loadBeforeAndLoadAfter() {
        val plugins = listOf(
            BetterNcmLoadPlugin("a", loadBefore = listOf("b")),
            BetterNcmLoadPlugin("b"),
        )
        assertEquals(listOf("a", "b"), BetterNcmLoadOrder.sort(plugins))
        val after = listOf(
            BetterNcmLoadPlugin("a", loadAfter = listOf("b")),
            BetterNcmLoadPlugin("b"),
        )
        assertEquals(listOf("b", "a"), BetterNcmLoadOrder.sort(after))
    }

    @Test
    fun missingDependencyThrows() {
        try {
            BetterNcmLoadOrder.sort(listOf(BetterNcmLoadPlugin("a", loadBefore = listOf("gone"))))
            throw AssertionError("expected throw")
        } catch (e: BetterNcmDependencyError) {
            assertEquals("gone", e.slug)
        }
        try {
            BetterNcmLoadOrder.sort(listOf(BetterNcmLoadPlugin("a", loadAfter = listOf("gone"))))
            throw AssertionError("expected throw")
        } catch (e: BetterNcmDependencyError) {
            assertEquals("gone", e.slug)
        }
    }

    @Test
    fun independentPluginsKeepInputOrder() {
        val plugins = listOf(
            BetterNcmLoadPlugin("c"),
            BetterNcmLoadPlugin("a"),
            BetterNcmLoadPlugin("b"),
        )
        assertEquals(listOf("c", "a", "b"), BetterNcmLoadOrder.sort(plugins))
    }
}

class BetterNcmManifestTest {
    @Test
    fun slugComesFromNameWhenOmitted() {
        val parsed = BetterNcmManifests.parse(
            """
            {"manifest_version":1,"name":"My Plugin","version":"0.1.0","injects":{"Main":[{"file":"main.js"}]}}
            """.trimIndent(),
        )!!
        assertEquals("My-Plugin", parsed.slug)
        assertEquals(listOf("main.js"), parsed.mainInjects())
        assertEquals("startup_script.js", parsed.startupScript)
        assertEquals("-Plugin", BetterNcmManifests.slugFromName("你好 Plugin"))
    }

    @Test
    fun keepsExplicitSlugAndLoadEdges() {
        val parsed = BetterNcmManifests.parse(
            """
            {"manifest_version":1,"name":"N","slug":"kept","version":1,
             "loadBefore":["a"],"loadAfter":["b"],
             "injects":{"Main":[{"file":"src/app.js"},{"file":"skip.css"}],"Other":[{"file":"x.js"}]}}
            """.trimIndent(),
        )!!
        assertEquals("kept", parsed.slug)
        assertEquals("1", parsed.version)
        assertEquals(listOf("a"), parsed.loadBefore)
        assertEquals(listOf("b"), parsed.loadAfter)
        assertEquals(listOf("src/app.js"), parsed.mainInjects())
        assertEquals(listOf("x.js"), parsed.injects["Other"])
        assertEquals("boot.js", BetterNcmManifests.parse(
            """
            {"manifest_version":1,"name":"N","slug":"kept","version":"1","startup_script":"./boot.js",
             "injects":{"Main":[{"file":"main.js"}]}}
            """.trimIndent(),
        )!!.startupScript)
        assertNull(BetterNcmManifests.parse(
            """
            {"manifest_version":1,"name":"N","slug":"kept","version":"1","startup_script":"../boot.js",
             "injects":{"Main":[{"file":"main.js"}]}}
            """.trimIndent(),
        )!!.startupScript)
    }

    @Test
    fun rejectsBadManifests() {
        assertNull(BetterNcmManifests.parse("""{"manifest_version":2,"name":"N","version":"1"}"""))
        assertNull(BetterNcmManifests.parse("""{"manifest_version":1,"version":"1"}"""))
        assertNull(
            BetterNcmManifests.parse(
                """{"manifest_version":1,"name":"N","version":"1","injects":{"Main":[{"file":"../x.js"}]}}""",
            ),
        )
        val root = Files.createTempDirectory("bncm-manifest").toFile()
        assertNull(BetterNcmManifests.read(File(root, "manifest.json")))
        val file = File(root, "manifest.json")
        file.writeText(
            """{"manifest_version":1,"name":"Demo","version":"1.0.0","injects":{"Main":[{"file":"main.js"}]}}""",
            Charsets.UTF_8,
        )
        val read = BetterNcmManifests.read(file)!!
        assertEquals("Demo", read.slug)
        assertEquals(listOf("main.js"), read.mainInjects())
    }
}

class BetterNcmDebounceTest {
    @Test
    fun onlyTheLatestCallFiresAfterItsOwnWait() {
        val debounce = BetterNcmDebounce(100)
        debounce.call(0, listOf("a"))
        assertNull(debounce.poll(99))
        debounce.call(50, listOf("b", 1))
        assertNull(debounce.poll(149))
        assertEquals(listOf("b", 1), debounce.poll(150))
        assertNull(debounce.poll(200))
    }
}

class BetterNcmExecTest {
    @Test
    fun safeCommandsStayInsideTheSandbox() {
        val root = Files.createTempDirectory("bncm-exec").toFile()
        val host = BetterNcmHost(File(root, "data"), "com.example.demo", File(root, "plugin"))
        assertTrue(host.exec("pwd"))
        assertTrue(host.exec("echo hi"))
        assertTrue(host.exec("ls"))
        assertTrue(host.exec("mkdir D:\\box"))
        assertTrue(host.exec("touch D:\\box\\a.txt"))
        assertTrue(host.exec("cat \"D:\\box\\a.txt\""))
        assertTrue(host.exec("cp D:\\box\\a.txt C:\\b.txt"))
        assertEquals("", host.readFileText("C:\\b.txt"))
        assertTrue(host.exec("mv C:\\b.txt C:\\c.txt"))
        assertTrue(host.exists("C:\\c.txt"))
        assertTrue(host.exec("rm C:\\c.txt"))
        assertFalse(host.exists("C:\\c.txt"))
    }

    @Test
    fun shellMetacharactersAndUnknownCommandsFail() {
        val root = Files.createTempDirectory("bncm-exec-deny").toFile()
        val host = BetterNcmHost(File(root, "data"), "com.example.demo", File(root, "plugin"))
        assertFalse(host.exec("ls && rm C:\\"))
        assertFalse(host.exec("cat a.txt | echo"))
        assertFalse(host.exec("cmd /c whoami"))
        assertFalse(host.exec("rm C:\\"))
        assertFalse(host.exec("ls C:\\no-such"))
        assertFalse(host.exec("cd C:\\no-such"))
        assertNull(BetterNcmExec.tokenize("echo \"unterminated"))
    }

    @Test
    fun commandAliasesStayInTheSandbox() {
        val root = Files.createTempDirectory("bncm-exec-alias").toFile()
        val host = BetterNcmHost(File(root, "data"), "com.example.demo", File(root, "plugin"))
        assertTrue(host.exec("mkdir D:\\box"))
        assertTrue(host.exec("touch D:\\box\\a.txt"))
        assertTrue(host.writeFileText("D:\\box\\a.txt", "body"))
        assertTrue(host.exec("dir D:\\box"))
        assertTrue(host.exec("cd D:\\box"))
        assertTrue(host.exec("type D:\\box\\a.txt"))
        assertTrue(host.exec("copy D:\\box\\a.txt C:\\d.txt"))
        assertEquals("body", host.readFileText("C:\\d.txt"))
        assertTrue(host.exec("move C:\\d.txt C:\\e.txt"))
        assertTrue(host.exec("del C:\\e.txt"))
        assertFalse(host.exists("C:\\e.txt"))
        assertFalse(host.remove("D:\\"))
    }
}

class BetterNcmStubApiTest {
    @Test
    fun fixedResultsMatchTheAgreedShapes() {
        val root = Files.createTempDirectory("bncm-stub").toFile()
        var opened: String? = null
        var picked = ""
        var reloaded = false
        val host = BetterNcmHost(
            File(root, "data"),
            "com.example.demo",
            File(root, "plugin"),
            openUrl = { opened = it; true },
            openFile = { filter, _ ->
                picked = filter
                "content://picked"
            },
            reloadSelf = { reloaded = true; true },
        )
        assertEquals("", host.ncmPath())
        assertEquals(linkedMapOf("x" to 0, "y" to 0), host.winPos())
        assertFalse(host.showConsole())
        assertTrue(host.setRoundedCorner())
        assertTrue(host.reloadPlugins())
        assertTrue(host.succeededHijacks().isEmpty())
        assertFalse(host.nativeCall())
        assertEquals("", host.http("GET", "/app/ncmpath").text)
        assertEquals("{\"x\":0,\"y\":0}", host.http("GET", "/app/get_win_position").text)
        assertEquals(500, host.http("GET", "/app/show_console").status)
        assertEquals(200, host.http("GET", "/app/set_rounded_corner?enable=true").status)
        assertEquals(200, host.http("GET", "/app/reload_plugin").status)
        assertEquals("[]", host.http("GET", "/app/get_succeeded_hijacks").text)
        val png = host.whiteScreenshot()
        assertEquals(0x89.toByte(), png[0])
        assertEquals(0x50.toByte(), png[1])
        assertTrue(host.openExternal("https://example.com/a"))
        assertEquals("https://example.com/a", opened)
        assertFalse(host.openExternal("file:///etc/passwd"))
        assertFalse(host.openExternal("javascript:alert(1)"))
        assertEquals("content://picked", host.pickFile("Text\u0000*.txt\u0000", ""))
        assertEquals("Text\u0000*.txt\u0000", picked)
        assertTrue(host.reload())
        assertTrue(reloaded)
        assertEquals(listOf("text/plain"), BetterNcmLinks.mimeTypes("*.txt"))
        assertEquals(listOf("*/*"), BetterNcmLinks.mimeTypes(""))
        assertEquals(200, host.http("POST", "/app/exec", "ls").status)
        assertEquals(500, host.http("POST", "/app/exec", "format C:").status)
        assertEquals(500, host.http("POST", "/app/exec_ele", "cmd").status)
        val shot = host.http("GET", "/app/bg_screenshot")
        assertEquals(200, shot.status)
        assertWhitePng(shot.bytes!!)
        assertWhitePng(host.whiteScreenshot())
        assertEquals(
            "content://picked",
            host.http("GET", "/app/open_file_dialog?filter=*.txt&initialDir=C:\\").text,
        )
        val mountedFile = host.http("GET", "/fs/mount_file?path=a").text
        val mountedDir = host.http("GET", "/fs/mount_dir?path=a").text
        assertTrue(mountedFile.startsWith("file:"))
        assertTrue(mountedDir.startsWith("file:"))
        assertEquals("true", host.channel("os.navigateExternal", """["https://example.com/b"]"""))
        assertEquals("https://example.com/b", opened)
        assertTrue(host.cmder("os.querySystemFonts", "[]").startsWith("["))
        assertTrue(host.writeFileText("/__TEST_FAILED__.txt", "reason"))
        assertEquals("reason", host.readFileText("C:\\__TEST_FAILED__.txt"))
        assertTrue(host.writeFileText("/__TEST_SUCCEEDED__.txt", "ok"))
        assertEquals("ok", host.readFileText("C:\\__TEST_SUCCEEDED__.txt"))
    }
}

class BetterNcmDomTest {
    @Test
    fun createsNodesAndFindsThem() {
        val doc = BetterNcmDocument()
        val child = doc.create("span", mapOf("innerText" to "hi", "class" to listOf("mark")), emptyList())!!
        val root = doc.create(
            "div",
            mapOf("id" to "box", "class" to listOf("card"), "style" to mapOf("color" to "red")),
            listOf(child),
        )!!
        assertEquals(root, doc.query("#box"))
        assertEquals(root, doc.query("div.card"))
        assertEquals(child, doc.query(".mark"))
        assertEquals(child, doc.query("span"))
        assertEquals(listOf(child), doc.queryAll(".mark"))
        assertNull(doc.query(".missing"))
        assertTrue(doc.addClass(root, "wide"))
        assertTrue(doc.hasClass(root, "wide"))
        assertTrue(doc.setClassName(root, "plain"))
        assertEquals(listOf(root), doc.queryAll(".plain"))
        val titled = doc.create("div", mapOf("class" to "one two"), emptyList())!!
        assertEquals(listOf(titled), doc.queryAll(".two"))
        assertEquals("hi", doc.node(child)!!.text)
        assertEquals("red", doc.node(root)!!.style["color"])
        assertTrue(doc.append(root, child))
        assertNull(doc.create("../x", emptyMap(), emptyList()))
    }

    @Test
    fun hostAcceptsJsonAndMirrorsTheNode() {
        val root = Files.createTempDirectory("bncm-dom-host").toFile()
        val mirrored = ArrayList<String>()
        val host = BetterNcmHost(
            File(root, "data"),
            "com.example.demo",
            File(root, "plugin"),
            mirrorDom = { mirrored.add(it) },
        )
        val parent = host.createNode(
            "div",
            """{"id":"box","class":["card"],"style":{"color":"red"},"innerText":"t"}""",
            "[]",
        )
        val child = host.createNode("span", """{"class":["mark"]}""", "[$parent]")
        assertEquals(parent, host.queryNode("#box"))
        assertEquals(parent, host.queryNode("div.card"))
        assertEquals(child, host.queryNode(".mark"))
        assertTrue(host.appendNode(parent!!, child!!))
        assertFalse(host.appendNode(parent, 999))
        assertNull(host.createNode("bad tag", "{}", "[]"))
        assertNull(host.queryNode(".missing"))
        assertTrue(mirrored.any { it.contains("__bncmUpsert") && it.contains("\"div\"") })
    }

    @Test
    fun channelSeekAndMountUseTheRealSandbox() {
        val root = Files.createTempDirectory("bncm-transport").toFile()
        var seek = -1L
        var volume = -1f
        val host = BetterNcmHost(
            File(root, "data"),
            "com.example.demo",
            File(root, "plugin"),
            control = { op, arg ->
                when (op) {
                    "seek" -> seek = arg.toLong()
                    "volume" -> volume = arg.toFloat()
                }
                "true"
            },
        )
        assertEquals("true", host.channel("audioplayer.seek", """["song","token",12.25]"""))
        assertEquals(12250L, seek)
        assertEquals("true", host.channel("audioplayer.setVolume", """["","",1.4]"""))
        assertEquals(1f, volume)
        assertEquals("null", host.channel("not.a.player", "[]"))
        assertTrue(host.mkdir("/FluentMusicCover-Cache"))
        val mounted = host.mount("/FluentMusicCover-Cache")
        assertTrue(mounted.startsWith("file:"))
        assertTrue(mounted.contains("FluentMusicCover-Cache"))
        root.deleteRecursively()
    }
}

class BetterNcmScriptSurfaceTest {
    @Test
    fun injectedScriptDefinesEveryPublicCall() {
        val script = BetterNcmRuntime.scriptSource()
        val names = listOf(
            "readDir", "readFileText", "readFile", "writeFileText", "writeFile", "mkdir", "exists",
            "remove", "unzip", "mountFile", "mountDir", "rename", "watchDirectory",
            "getBetterNCMVersion", "getDataPath", "readConfig", "writeConfig", "isLightTheme", "exec",
            "takeBackgroundScreenshot", "getNCMWinPos", "reloadPlugins", "getNCMPath", "showConsole",
            "setRoundedCorner", "openFileDialog", "getSucceededHijacks",
            "findNativeFunction", "openUrl", "getNCMPackageVersion", "getNCMFullVersion", "getNCMVersion",
            "getNCMBuild", "searchApiFunction", "searchForData", "findApiFunction", "getPlayingSong", "getPlaying",
            "eapiRequest",
            "debounce", "waitForFunction", "delay", "waitForElement", "dom",
            "fail", "success", "betterncmFetch", "reload",
            "onLoad", "onAllPluginsLoaded", "onConfig", "getConfig", "setConfig",
            "getItem", "setItem", "removeItem",
            "datapath", "ncmpath", "reloadIgnoreCache", "restart", "crash",
            "getRegisteredAPIs", "call",
        )
        val missing = names.filter { !script.contains("$it: function") }
        assertEquals(emptyList<String>(), missing)
        assertTrue(script.contains("mountFile: function(path) { return Promise.resolve(betterncm_native.fs.mountFile(path)); }"))
        assertTrue(script.contains("mountDir: function(path) { return Promise.resolve(betterncm_native.fs.mountDir(path)); }"))
        assertTrue(script.contains("function fetch(url, options)"))
        assertTrue(script.contains("showConsole: function() { return Promise.resolve(false); }"))
        assertTrue(script.contains("setRoundedCorner: function() { return Promise.resolve(true); }"))
        assertTrue(script.contains("reloadPlugins: function() { return Promise.resolve(true); }"))
        assertTrue(script.contains("getNCMPath: function() { return Promise.resolve(\"\"); }"))
        assertTrue(script.contains("getSucceededHijacks: function() { return Promise.resolve([]); }"))
        assertTrue(script.contains("watchDirectory: function(path, callback)"))
        assertTrue(script.contains("call: function() { return false; }"))
        assertTrue(script.contains("getRegisteredAPIs: function() { return []; }"))
        assertTrue(script.contains("reloadIgnoreCache: function() { return !!__zmusicBncm(\"app.reload\"); }"))
        assertTrue(script.contains("restart: function() { return !!__zmusicBncm(\"app.reload\"); }"))
        assertTrue(script.contains("crash: function() { return false; }"))
        assertTrue(script.contains("function MutationObserver(callback)"))
        assertTrue(script.contains("function EventTarget()"))
        assertTrue(script.contains("globalThis.addEventListener"))
        assertTrue(script.contains("globalThis.removeEventListener"))
        assertTrue(script.contains("globalThis.dispatchEvent"))
        assertTrue(script.contains("globalThis[\"await\"]"))
        assertTrue(script.contains("text: function() { return text; }"))
        assertTrue(script.contains("function Event(type, init)"))
        assertTrue(script.contains("function CustomEvent(type, init)"))
        assertTrue(script.contains("new Proxy(__bncmStorage"))
        assertTrue(script.contains("function __bncmConfig()"))
        assertTrue(script.contains("ncmpath: function() { return \"\"; }"))
        assertTrue(script.contains("mountFile: function(path) { return String(__zmusicBncm(\"fs.mount\", String(path)) || \"\"); }"))
        assertTrue(script.contains("mountDir: function(path) { return String(__zmusicBncm(\"fs.mount\", String(path)) || \"\"); }"))
    }
}

private fun assertWhitePng(png: ByteArray) {
    assertEquals(0x89, png[0].toInt() and 0xFF)
    assertEquals(0x50, png[1].toInt() and 0xFF)
    assertEquals(0x4E, png[2].toInt() and 0xFF)
    assertEquals(0x47, png[3].toInt() and 0xFF)
    var index = 8
    var width = 0
    var height = 0
    var idat = ByteArray(0)
    while (index + 8 <= png.size) {
        val length = readPngInt(png, index)
        val type = String(png, index + 4, 4, Charsets.US_ASCII)
        val data = png.copyOfRange(index + 8, index + 8 + length)
        if (type == "IHDR") {
            width = readPngInt(data, 0)
            height = readPngInt(data, 4)
            assertEquals(8, data[8].toInt() and 0xFF)
            assertEquals(2, data[9].toInt() and 0xFF)
        }
        if (type == "IDAT") idat += data
        if (type == "IEND") break
        index += 12 + length
    }
    assertTrue(width > 0 && height > 0)
    val inflater = Inflater()
    inflater.setInput(idat)
    val raw = ByteArray(height * (1 + width * 3))
    val inflated = inflater.inflate(raw)
    inflater.end()
    assertEquals(raw.size, inflated)
    for (y in 0 until height) {
        val row = y * (1 + width * 3)
        assertEquals(0, raw[row].toInt() and 0xFF)
        for (x in 0 until width * 3) {
            assertEquals(0xFF, raw[row + 1 + x].toInt() and 0xFF)
        }
    }
}

private fun readPngInt(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}

class BetterNcmLibsTest {
    @Test
    fun lyricStackComesBeforeTheOtherBundledLibs() {
        assertEquals("LibEAPIRequest", BetterNcmLibs.modules.first().slug)
        assertTrue(BetterNcmLibs.modules.map { it.slug }.indexOf("liblyric") > 0)
        assertTrue(BetterNcmLibs.modules.any { it.slug == "RefinedNowPlaying" })
        assertEquals(BetterNcmEapiRoutes.Kind.LYRIC, BetterNcmEapiRoutes.kind("/api/song/lyric/v1"))
        assertEquals(BetterNcmEapiRoutes.Kind.DETAIL, BetterNcmEapiRoutes.kind("/api/v3/song/detail"))
        assertEquals(BetterNcmEapiRoutes.Kind.URL, BetterNcmEapiRoutes.kind("/api/song/enhance/player/url"))
        assertEquals(BetterNcmEapiRoutes.Kind.PLAYLIST, BetterNcmEapiRoutes.kind("/api/v6/playlist/detail"))
        assertEquals(listOf(7L, 8L), BetterNcmEapiRoutes.ids(emptyMap(), mapOf("c" to """[{"id":7},{"id":8}]""")))
        val boot = BetterNcmLibs.bootScript()
        assertTrue(boot.contains("eapiRequest"))
        assertTrue(boot.contains("getSongDetail"))
        assertTrue(boot.contains("loadStylesheet"))
        assertTrue(boot.contains("audio-id-updated"))
        assertTrue(boot.contains("injects = [entry]"))
        assertFalse(boot.contains("parseLyric"))
        assertTrue(boot.contains("ReactDOM"))
        assertTrue(boot.contains("InfLinkApi"))
        assertTrue(boot.contains("originFromTrack"))
        assertTrue(boot.contains("triggerRegisterCall(\"End\""))
        assertTrue(boot.contains("__bncmApplySheet"))
        assertTrue(BetterNcmRuntime.scriptSource().contains("function MutationObserver(callback)"))
        assertEquals(
            "https://s1.ax1x.com/2022/06/11/XcOy4J.jpg",
            BetterNcmThemeSignals.imageUrl("", mapOf("--Unbounded-background" to "url(https://s1.ax1x.com/2022/06/11/XcOy4J.jpg)")),
        )
        assertTrue(BetterNcmThemeSignals.frosted(".bar{backdrop-filter:blur(16px)}"))
        assertTrue(boot.contains("triggerRegisterCall(\"PlayState\""))
        assertTrue(boot.contains("triggerRegisterCall(\"Volume\""))
        assertTrue(boot.contains("_envAdapter"))
        assertTrue(boot.contains("callAdapter"))
        assertTrue(boot.contains("function getPlaying()"))
        assertTrue(boot.contains("__bncmDocEvents.addEventListener"))
        assertTrue(boot.contains("triggerRegisterCall(\"Load\""))
        assertTrue(boot.contains("playbackRate"))
        assertTrue(boot.contains("currentAudioPlayer"))
        assertTrue(boot.contains("updateCurrentAudioPlayer"))
        assertEquals(1500L, BetterNcmTransport.seekMs(listOf("", "", 1.5)))
        assertEquals(0.0f, BetterNcmTransport.unit(listOf("", "", -0.2), 2))
        assertEquals(1f, BetterNcmTransport.unit(listOf("", "", 4), 2))
        assertEquals(2.5f, BetterNcmTransport.rate("2.5"))
        assertEquals(0.1f, BetterNcmTransport.rate("0.01"))
        assertTrue(boot.contains("addTemporaryCustomSource"))
        val artists = BetterNcmPlaying.artistEntries("甲 / 乙")
        assertEquals(listOf("甲", "乙"), artists.map { it["name"] })
        assertNull(BetterNcmThemeSignals.accent(mapOf("--n-color" to "#111111")))
        assertEquals(listOf("gone.txt"), BetterNcmWatch.changed(mapOf("gone.txt" to 1L), emptyMap()))
        assertEquals(listOf("new.txt"), BetterNcmWatch.changed(emptyMap(), mapOf("new.txt" to 2L)))
        assertEquals(emptyList<String>(), BetterNcmWatch.changed(mapOf("same.txt" to 3L), mapOf("same.txt" to 3L)))
        assertEquals(listOf("A", "B"), BetterNcmFonts.names(listOf("B.ttf", "A.otf", "A.ttf")))
    }

    @Test
    fun pluginZipKeepsStylesheetAndRejectsExecutables() {
        val root = Files.createTempDirectory("bncm-pkg").toFile()
        val zip = File(root, "Unbounded.plugin")
        val dest = File(root, "out")
        ZipOutputStream(zip.outputStream()).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            put(
                "manifest.json",
                """{"manifest_version":1,"name":"Unbounded","slug":"Unbounded","version":"0.1.0","injects":{"Main":[{"file":"main.js"}]}}""",
            )
            put("main.js", "plugin.onLoad(function(){});")
            put("theme.css", "body{color:red}")
        }
        val unpacked = BetterNcmPackage.unpack(zip, dest)
        assertTrue(unpacked is BetterNcmUnpack.Ok)
        assertTrue(File(dest, "theme.css").isFile)
        assertEquals(100, BetterNcmManifests.versionCode("0.1.0"))
        val bad = File(root, "bad.plugin")
        ZipOutputStream(bad.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("run.exe"))
            out.write(byteArrayOf(1))
            out.closeEntry()
        }
        assertTrue(BetterNcmPackage.unpack(bad, File(root, "bad-out")) is BetterNcmUnpack.Invalid)
        root.deleteRecursively()
    }
}

class BetterNcmJointTest {
    @Test
    fun documentQueriesFollowClassIdTextAndChildrenTogether() {
        val doc = BetterNcmDocument()
        val root = doc.create("div", mapOf("id" to "box", "class" to "alpha  beta"), emptyList())!!
        val child = doc.create("span", mapOf("class" to listOf("mark")), emptyList())!!
        assertEquals(root, doc.query("#box"))
        assertEquals(root, doc.query("DIV.alpha"))
        assertEquals(listOf(root), doc.queryAll("div.alpha#box"))
        assertEquals(listOf(root), doc.queryAll(".beta"))
        assertTrue(doc.append(root, child))
        assertEquals(listOf(child), doc.node(root)!!.children)
        assertEquals(listOf(child), doc.queryAll("span.mark"))

        assertTrue(doc.setClassName(root, "plain"))
        assertEquals(emptyList<Int>(), doc.queryAll(".alpha"))
        assertEquals(listOf(root), doc.queryAll(".plain"))
        assertFalse(doc.hasClass(root, "alpha"))
        assertTrue(doc.addClass(root, "plain"))
        assertEquals(listOf("plain"), doc.node(root)!!.classes.toList())
        assertFalse(doc.addClass(root, " "))
        assertFalse(doc.addClass(999, "plain"))
        assertTrue(doc.removeClass(root, "plain"))
        assertFalse(doc.removeClass(root, "plain"))
        assertNull(doc.query(".plain"))

        assertTrue(doc.setAttr(root, "id", "next"))
        assertNull(doc.query("#box"))
        assertEquals(root, doc.query("#next"))
        assertFalse(doc.setAttr(root, "", "x"))
        assertTrue(doc.setText(child, "歌词"))
        assertEquals("歌词", doc.node(child)!!.text)
        assertFalse(doc.setText(999, "x"))

        assertTrue(doc.clearChildren(root))
        assertTrue(doc.node(root)!!.children.isEmpty())
        assertEquals(child, doc.query("span.mark"))
        assertFalse(doc.clearChildren(999))
        assertEquals(emptyList<Int>(), doc.queryAll(" "))
    }

    @Test
    fun hostMirrorBodyFlagsAndQueriesDescribeTheSameNode() {
        val root = Files.createTempDirectory("bncm-joint-dom").toFile()
        val mirrored = ArrayList<String>()
        val host = BetterNcmHost(
            File(root, "data"),
            "com.example.demo",
            File(root, "plugin"),
            mirrorDom = { mirrored.add(it) },
        )
        val body = host.createNode("body", "{}", "[]")!!
        val before = mirrored.size
        assertTrue(host.setClass(body, "BGEnhanced", true))
        assertTrue(host.hasClass(body, "BGEnhanced"))
        assertEquals(listOf("BGEnhanced"), host.classNames(body))
        assertEquals(listOf(body), host.queryAll("body.BGEnhanced"))
        val added = mirrored.drop(before)
        assertTrue(added.any { it.contains("classList.add") && it.contains("BGEnhanced") })
        assertTrue(added.any { it.contains("__bncmUpsert") && it.contains("BGEnhanced") })

        val div = host.createNode("div", """{"class":"card"}""", "[]")!!
        val divBefore = mirrored.size
        assertTrue(host.setClass(div, "wide", true))
        assertEquals(listOf(div), host.queryAll("div.wide"))
        assertTrue(mirrored.drop(divBefore).none { it.contains("classList.add") })

        assertTrue(host.setClassName(body, "light"))
        assertFalse(host.hasClass(body, "BGEnhanced"))
        assertEquals(body, host.queryNode("body.light"))
        assertTrue(mirrored.any { it.contains("classList.remove") && it.contains("BGEnhanced") })
        assertTrue(mirrored.any { it.contains("classList.add") && it.contains("light") })

        assertFalse(host.setClass(body, "missing", false))
        assertFalse(host.hasClass(body, "missing"))
        assertFalse(host.setClass(body, " ", true))
        assertFalse(host.setClass(999, "x", true))

        assertTrue(host.setAttr(div, "id", "panel"))
        assertTrue(host.setText(div, "标题"))
        assertEquals(div, host.queryNode("#panel"))
        assertEquals("标题", host.document.node(div)!!.text)
        assertEquals("div", host.nodeTag(div))
        assertTrue(mirrored.last().contains("__bncmUpsert") && mirrored.last().contains("标题"))

        val child = host.createNode("span", """{"class":["mark"]}""", "[]")!!
        assertTrue(host.appendNode(div, child))
        assertTrue(host.clearChildren(div))
        assertTrue(host.document.node(div)!!.children.isEmpty())
        assertEquals(child, host.queryNode(".mark"))
        assertFalse(host.clearChildren(999))
        root.deleteRecursively()
    }

    @Test
    fun watchStampAndDiffReportTheSameSandboxChanges() {
        assertEquals(
            listOf("b.txt", "c.txt", "a.txt"),
            BetterNcmWatch.changed(
                linkedMapOf("a.txt" to 1L, "b.txt" to 2L),
                linkedMapOf("b.txt" to 9L, "c.txt" to 3L),
            ),
        )
        val root = Files.createTempDirectory("bncm-joint-watch").toFile()
        val data = File(root, "data")
        val plugin = File(root, "plugin")
        val host = BetterNcmHost(data, "com.example.demo", plugin)
        assertNull(host.watchStamp("../outside"))
        assertNull(host.watchStamp(""))
        assertNull(host.watchStamp("C:\\missing"))
        assertTrue(host.writeFileText("note.txt", "a"))
        assertNull(host.watchStamp("C:\\note.txt"))

        assertTrue(host.mkdir("C:\\watch"))
        val empty = host.watchStamp("C:\\watch")!!
        assertTrue(empty.isEmpty())
        assertTrue(host.writeFileText("C:\\watch\\a.txt", "1"))
        val withA = host.watchStamp("C:\\watch")!!
        assertEquals(listOf("a.txt"), BetterNcmWatch.changed(empty, withA))
        assertTrue(host.writeFileText("C:\\watch\\b.txt", "2"))
        val withB = host.watchStamp("watch")!!
        assertEquals(listOf("b.txt"), BetterNcmWatch.changed(withA, withB))

        val fileA = File(data, "C/watch/a.txt")
        assertTrue(fileA.setLastModified(withB.getValue("a.txt") + 10_000))
        val bumped = host.watchStamp("C:/watch")!!
        assertEquals(listOf("a.txt"), BetterNcmWatch.changed(withB, bumped))
        assertTrue(host.remove("C:\\watch\\b.txt"))
        val afterDelete = host.watchStamp("C:\\watch")!!
        assertEquals(listOf("b.txt"), BetterNcmWatch.changed(bumped, afterDelete))
        assertFalse(afterDelete.containsKey("b.txt"))

        File(plugin, "pack.js").writeText("x")
        val mounted = host.watchStamp("C:\\plugins_runtime")!!
        assertTrue(mounted.containsKey("pack.js"))
        assertNull(host.watchStamp("C:\\plugins_runtime\\pack.js"))
        root.deleteRecursively()
    }

    @Test
    fun clearingLocalStorageDropsPluginConfigForTheNextHost() {
        val root = Files.createTempDirectory("bncm-joint-ls").toFile()
        val data = File(root, "data")
        val host = BetterNcmHost(data, "com.example.demo", File(root, "plugin"))
        assertTrue(host.localSet("theme", "dark"))
        assertTrue(host.configSet("demo", "on", true))
        assertTrue(host.localKeys().containsAll(listOf("theme", BetterNcmHost.configKey("demo"))))
        assertEquals(true, host.configGet("demo", "on"))
        assertEquals("dark", host.localGet("theme"))

        assertTrue(host.localRemove("theme"))
        assertFalse(host.localKeys().contains("theme"))
        assertEquals(true, host.configGet("demo", "on"))
        assertTrue(host.localClear())
        assertTrue(host.localKeys().isEmpty())
        assertNull(host.localGet("theme"))
        assertTrue(host.configGet("demo", "on") === BetterNcmHost.Missing)

        val reopened = BetterNcmHost(data, "com.example.demo", File(root, "plugin"))
        assertTrue(reopened.localKeys().isEmpty())
        assertTrue(reopened.configGet("demo", "on") === BetterNcmHost.Missing)
        root.deleteRecursively()
    }

    @Test
    fun mountHttpChannelAndCmderUseTheSameSandboxAndPlayer() {
        val root = Files.createTempDirectory("bncm-joint-io").toFile()
        var opened: String? = null
        val ops = ArrayList<Pair<String, String>>()
        val host = BetterNcmHost(
            File(root, "data"),
            "com.example.demo",
            File(root, "plugin"),
            openUrl = { opened = it; true },
            control = { op, arg ->
                ops.add(op to arg)
                "true"
            },
        )
        assertEquals(404, host.http("POST", "/fs/mount_dir?path=cache").status)
        val mounted = host.http("GET", "/fs/mount_dir?path=cache")
        assertEquals(200, mounted.status)
        assertTrue(mounted.text.startsWith("file:"))
        assertTrue(mounted.text.contains("cache"))
        assertTrue(host.exists("cache"))
        assertEquals(200, host.http("POST", "/fs/write_file_text?path=cache/n.txt", "lyric").status)
        assertEquals("lyric", host.http("GET", "/fs/read_file_text?path=C:\\cache\\n.txt").text)
        assertEquals("lyric", host.readFileText("cache/n.txt"))
        val fileMount = host.http("GET", "/fs/mount_file?path=cache/n.txt")
        assertEquals(200, fileMount.status)
        assertTrue(fileMount.text.contains("n.txt"))
        assertEquals(500, host.http("GET", "/fs/mount_dir?path=../outside").status)

        val seek = """["song","token",2]"""
        assertEquals("true", host.channel("audioplayer.seek", seek))
        assertEquals("true", host.cmder("audioplayer.seek", seek))
        assertEquals(listOf("seek" to "2000", "seek" to "2000"), ops)
        assertEquals("false", host.cmder("os.navigateExternal", """["javascript:alert(1)"]"""))
        assertNull(opened)
        assertEquals("true", host.channel("os.navigateExternal", """["https://example.com/song"]"""))
        assertEquals("https://example.com/song", opened)
        assertEquals("null", host.cmder("not.a.player", "[]"))
        assertEquals("null", host.channel("not.a.player", "[]"))
        val listed = File("/system/fonts").list()?.toList().orEmpty()
        assertEquals(
            PluginJson.stringify(BetterNcmFonts.names(listed)),
            host.cmder("os.querySystemFonts", "[]"),
        )
        assertEquals(
            listOf(".hidden", "Segoe", "noext"),
            BetterNcmFonts.names(listOf("C:\\Windows\\Fonts\\Segoe.ttf", "noext", ".hidden")),
        )
        root.deleteRecursively()
    }

    @Test
    fun stylesheetFileFeedsThemeLookMirrorAndHttpTogether() {
        val root = Files.createTempDirectory("bncm-joint-style").toFile()
        val looks = ArrayList<BetterNcmLook>()
        val mirrored = ArrayList<String>()
        val host = BetterNcmHost(
            File(root, "data"),
            "com.example.demo",
            File(root, "plugin"),
            mirrorDom = { mirrored.add(it) },
            themeLook = { looks.add(it) },
        )
        val css = ".bar{backdrop-filter:blur(8px);background-image:url(https://img.example/a.jpg)}"
        val vars = mapOf("--md-accent-color" to "#336699")
        assertTrue(host.writeFileText("theme.css", css))
        val read = host.readFileText("C:\\theme.css")
        assertEquals(css, read)
        assertEquals(css, host.http("GET", "/fs/read_file_text?path=theme.css").text)
        val fromFile = BetterNcmThemeSignals.imageUrl(read!!, vars)
        val accent = BetterNcmThemeSignals.accent(vars)
        val frosted = BetterNcmThemeSignals.frosted(read)
        host.applyStyle(
            "Unbounded sheet",
            read,
            """{"vars":{"--md-accent-color":"#336699"},"flags":["prefab-transparency"],"unflags":["dark"]}""",
        )
        assertEquals(BetterNcmLook(fromFile, accent, frosted), looks.single())
        assertEquals("https://img.example/a.jpg", looks.single().imageUrl)
        assertEquals("#336699", looks.single().accent)
        assertTrue(looks.single().frosted)
        val script = mirrored.single()
        assertTrue(script.contains("bncm-style-Unboundedsheet"))
        assertTrue(script.contains("--md-accent-color"))
        assertTrue(script.contains("prefab-transparency"))
        assertTrue(script.contains("classList.remove"))
        assertTrue(script.contains("https://img.example/a.jpg"))

        host.applyStyle("plain", ".x{color:red}", "{}")
        assertEquals(BetterNcmLook(), looks.last())
        root.deleteRecursively()
    }

    @Test
    fun playingSongAndEapiReadTheSameIdsFromPlaybackAndRequest() {
        var snap = PluginPlaybackSnapshot(
            playing = true,
            positionMs = 2500,
            track = PluginTrackSnap(3, "名", "甲、乙", "专", 8000, "https://c"),
        )
        val dataRoot = Files.createTempDirectory("bncm-joint-play").toFile()
        val pluginRoot = Files.createTempDirectory("bncm-joint-play-plugin").toFile()
        val host = BetterNcmHost(
            dataRoot,
            "com.example.demo",
            pluginRoot,
            playback = { snap },
        )
        val song = host.playingSong()!!
        val data = song["data"] as Map<*, *>
        assertEquals(3L, data["id"])
        assertEquals("名", data["name"])
        assertEquals("https://c", (data["album"] as Map<*, *>)["picUrl"])
        assertEquals(listOf("甲", "乙"), (data["artists"] as List<*>).map { (it as Map<*, *>)["name"] })
        assertEquals("normal", BetterNcmPlaying.brief(song)["type"])
        assertEquals(2.5, (song["positionMs"] as Long) / 1000.0, 0.0)
        assertEquals(true, song["playing"])
        snap = snap.copy(playing = false, positionMs = 0)
        val paused = host.playingSong()!!
        assertEquals(false, paused["playing"])
        assertEquals(0L, paused["positionMs"])
        assertEquals(3L, (paused["data"] as Map<*, *>)["id"])
        snap = PluginPlaybackSnapshot()
        assertNull(host.playingSong())
        dataRoot.deleteRecursively()
        pluginRoot.deleteRecursively()

        assertEquals(BetterNcmEapiRoutes.Kind.LYRIC, BetterNcmEapiRoutes.kind("https://interface.music.163.com/api/song/lyric/v1"))
        assertEquals(42L, BetterNcmEapiRoutes.firstId(mapOf("id" to "42"), emptyMap()))
        assertEquals(
            listOf(1L, 2L),
            BetterNcmEapiRoutes.ids(emptyMap(), mapOf("c" to """[{"id":1},{"id":"2"}]""")),
        )
        assertEquals(BetterNcmEapiRoutes.Kind.DETAIL, BetterNcmEapiRoutes.kind("/api/v3/song/detail"))
        assertEquals(BetterNcmEapiRoutes.Kind.URL, BetterNcmEapiRoutes.kind("/api/song/enhance/player/url/v1"))
        assertEquals(9L, BetterNcmEapiRoutes.firstId(emptyMap(), mapOf("id" to 9, "br" to 999000)))
        assertEquals(999000, BetterNcmEapiRoutes.bitrate(mapOf("br" to 128000), mapOf("br" to 999000)))
        assertEquals(128000, BetterNcmEapiRoutes.bitrate(mapOf("br" to 128000), emptyMap()))
        assertEquals(BetterNcmEapiRoutes.Kind.PLAYLIST, BetterNcmEapiRoutes.kind("/api/v6/playlist/detail"))
        assertEquals(BetterNcmEapiRoutes.Kind.OTHER, BetterNcmEapiRoutes.kind("/api/comment/hot"))
        assertEquals(0L, BetterNcmEapiRoutes.firstId(emptyMap(), emptyMap()))
        var seen = ""
        val fetched = Files.createTempDirectory("bncm-eapi-fetch").toFile()
        val routed = BetterNcmHost(
            File(fetched, "data"),
            "com.example.demo",
            File(fetched, "plugin"),
            eapiCall = { seen = it; """{"code":200,"lrc":{"lyric":"la"}}""" },
            fetchExternal = { _, _, _, _ -> linkedMapOf("status" to 599, "text" to "raw", "b64" to "") },
        )
        val lyric = routed.fetch(
            "GET",
            "https://interface.music.163.com/api/song/lyric/v1?id=9&lv=0",
            "",
            "{}",
        )
        assertEquals(200, lyric["status"])
        assertTrue((lyric["text"] as String).contains("la"))
        assertTrue(seen.contains("9"))
        assertEquals(599, routed.fetch("GET", "https://example.com/plain", "", "{}")["status"])
        fetched.deleteRecursively()
    }

    @Test
    fun runtimeAndBootScriptsCallTheSameHostOperations() {
        val runtime = BetterNcmRuntime.scriptSource()
        val boot = BetterNcmLibs.bootScript()
        assertTrue(runtime.contains("reloadIgnoreCache: function() { return !!__zmusicBncm(\"app.reload\"); }"))
        assertTrue(runtime.contains("reload: function() { __zmusicBncm(\"app.reload\"); }"))
        assertTrue(runtime.contains("crash: function() { return false; }"))
        assertTrue(runtime.contains("watchDirectory: function(path, callback)"))
        assertTrue(runtime.contains("__zmusicBncm(\"fs.watch\""))
        assertTrue(runtime.contains("new Proxy(__bncmStorage"))
        assertTrue(runtime.contains("getItem: function(key)"))
        assertTrue(runtime.contains("eapiRequest: function(url, options)"))
        assertTrue(boot.contains("querySelectorAll: function(sel)"))
        assertTrue(boot.contains("__zmusicBncm(\"dom.queryAll\""))
        assertTrue(boot.contains("betterncm_native.fs.readFileText"))
        assertTrue(boot.contains("eapiRequest"))
        val lrc = boot.indexOf("origin.lrcid")
        val tid = boot.indexOf("track.tid")
        val dataId = boot.indexOf("data.id")
        assertTrue(lrc in 0 until tid && tid < dataId)
        val end = boot.indexOf("triggerRegisterCall(\"End\"")
        val load = boot.indexOf("triggerRegisterCall(\"Load\"")
        assertTrue(end in 0 until load)
        assertTrue(boot.contains("triggerRegisterCall(\"PlayProgress\", \"audioplayer\", id, progress / 1000, loadedNow)"))
        assertTrue(boot.contains("InfLinkApi"))
        assertTrue(boot.contains("getCurrentSong"))
        assertTrue(boot.contains("__bncmReactPaint"))
        assertTrue(boot.contains("useState: function(init)"))
        assertTrue(runtime.contains("function __bncmConfig()"))
        assertTrue(runtime.contains("makeBtn: function(text, onClick, smaller)"))
        assertTrue(runtime.contains("querySelector: function(sel)"))
        assertTrue(runtime.contains("__zmusicBncm(\"dom.html\""))
    }

    @Test
    fun htmlMarkupAndDescendantSelectorsFindTheSameButton() {
        val doc = BetterNcmDocument()
        val page = doc.create("div", mapOf("id" to "page"), emptyList())!!
        assertTrue(
            doc.setHtml(
                page,
                """
                <ul class="u-tab2"><li><a id="allsongs" class="j-flxg">全部歌曲</a></li></ul>
                <div id="settings">
                  <!-- keep -->
                  <style>.button{backdrop-filter:blur(12px)}</style>
                  <input id="applyButton" class="button textBox" type="button" value="go" />
                  <p>a &amp; b</p>
                </div>
                """.trimIndent(),
            ),
        )
        val link = doc.query(".u-tab2 li a")
        assertEquals(link, doc.query("#allsongs"))
        assertEquals("全部歌曲", doc.node(link!!)!!.text)
        assertEquals(link, doc.query("ul.u-tab2 a"))
        val settings = doc.query("#settings")!!
        val button = doc.query("#applyButton", settings)
        assertEquals(button, doc.query("input.button", settings))
        assertEquals(listOf("button", "textBox"), doc.node(button!!)!!.classes.toList())
        assertEquals("go", doc.node(button)!!.attrs["value"])
        assertNull(doc.query("#applyButton", doc.query("ul.u-tab2")!!))
        assertEquals("a & b", doc.node(doc.query("#settings p")!!)!!.text)
        assertTrue(doc.node(doc.query("style", settings)!!)!!.text.contains("backdrop-filter"))

        val root = Files.createTempDirectory("bncm-html").toFile()
        val looks = ArrayList<BetterNcmLook>()
        val host = BetterNcmHost(
            File(root, "data"),
            "com.example.demo",
            File(root, "plugin"),
            themeLook = { looks.add(it) },
        )
        val hostPage = host.createNode("div", """{"id":"page"}""", "[]")!!
        assertTrue(
            host.setHtml(
                hostPage,
                """<style>.button{backdrop-filter:blur(12px)}</style><input id="applyButton" class="button" type="button" value="go"/>""",
            ),
        )
        assertEquals(host.queryNode("#applyButton", hostPage), host.queryNode("div input.button"))
        assertTrue(looks.any { it.frosted })
        assertFalse(host.setHtml(999, "<p></p>"))
        assertTrue(host.setHtml(host.queryNode("#applyButton")!!, ""))
        assertNull(host.queryNode("#missing"))
        assertTrue(doc.setHtml(settings, "<p id=\"next\">x</p>"))
        assertNull(doc.query("#applyButton"))
        assertEquals("x", doc.node(doc.query("#next")!!)!!.text)
        root.deleteRecursively()
    }
}

class BetterNcmJobSignalTest {
    @Test
    fun hostCallsInOneTurnShareASingleDrain() {
        val signal = BetterNcmJobSignal()
        val queue = ArrayDeque<() -> Unit>()
        var turns = 0
        repeat(3) {
            signal.request({ queue.add(it) }) { turns += 1 }
        }
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(1, turns)
        signal.request({ queue.add(it) }) { turns += 1 }
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(2, turns)
    }

    @Test
    fun aRequestInsideTheDrainSchedulesTheNextTurn() {
        val signal = BetterNcmJobSignal()
        val queue = ArrayDeque<() -> Unit>()
        var turns = 0
        signal.request({ queue.add(it) }) {
            turns += 1
            if (turns == 1) {
                signal.request({ queue.add(it) }) { turns += 1 }
            }
        }
        queue.removeFirst().invoke()
        assertEquals(1, turns)
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(2, turns)
        assertTrue(queue.isEmpty())
    }
}
