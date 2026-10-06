package com.kite.zmusic.plugin

import java.io.File

/**
 * 每个插件一个沙盒目录。
 * 带盘符的路径落到沙盒里的同名文件夹：`C:\a` → `<沙盒>/C/a`，`D:\b` → `<沙盒>/D/b`。
 * 没有盘符的路径相对于数据目录 `C:\`。
 * `C:\plugins_runtime` 是这个插件自己的包目录，看不到别的插件。
 */
internal object BetterNcmPaths {
    const val DATA_DRIVE = "C"
    const val PLUGINS = "plugins_runtime"
    const val DATA_PATH_NATIVE = "$DATA_DRIVE:/"

    fun presentDataPath(native: String = DATA_PATH_NATIVE): String = native.replace('/', '\\')

    fun pluginPath(): String = "$DATA_DRIVE:\\$PLUGINS"

    sealed class Place {
        data object PluginsMount : Place()
        data class OnDisk(val file: File) : Place()
    }

    fun resolve(raw: String, sandbox: File, pluginRoot: File): Place? {
        if (raw.isEmpty() || raw.indexOf('\u0000') >= 0) return null
        val slashed = raw.replace('\\', '/')
        val drive: String
        val body: String
        if (slashed.length >= 2 && slashed[1] == ':' && slashed[0].isLetter()) {
            drive = slashed[0].uppercaseChar().toString()
            body = slashed.substring(2)
        } else {
            drive = DATA_DRIVE
            body = slashed
        }
        val stack = ArrayDeque<String>()
        for (part in body.split('/')) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") {
                if (stack.isEmpty()) return null
                stack.removeLast()
                continue
            }
            if (part.length == 1 && part[0] == '.') return null
            stack.addLast(part)
        }
        if (drive == DATA_DRIVE && stack.firstOrNull() == PLUGINS) {
            if (stack.size == 1) return Place.PluginsMount
            return under(pluginRoot, stack.drop(1))?.let { Place.OnDisk(it) }
        }
        return under(File(sandbox, drive), stack)?.let { Place.OnDisk(it) }
    }

    fun virtual(sandbox: File, pluginRoot: File, file: File): String? {
        val pluginRel = relative(pluginRoot, file)
        if (pluginRel != null) {
            return join(DATA_DRIVE, listOf(PLUGINS) + pluginRel)
        }
        val sandRel = relative(sandbox, file) ?: return null
        if (sandRel.isEmpty()) return null
        val drive = sandRel.first()
        if (drive.length != 1 || !drive[0].isLetter()) return null
        return join(drive, sandRel.drop(1))
    }

    fun join(drive: String, parts: List<String>): String {
        val letter = drive.uppercase()
        if (parts.isEmpty()) return "$letter:\\"
        return "$letter:\\" + parts.joinToString("\\")
    }

    private fun relative(root: File, file: File): List<String>? {
        val base = root.canonicalFile
        val target = file.canonicalFile
        if (target == base) return emptyList()
        val prefix = base.path + File.separator
        if (!target.path.startsWith(prefix)) return null
        return target.path.removePrefix(prefix).split(File.separator).filter { it.isNotEmpty() }
    }

    private fun under(root: File, parts: List<String>): File? {
        var cur = root
        for (part in parts) {
            cur = File(cur, part)
        }
        val base = root.canonicalFile
        val target = cur.canonicalFile
        if (target == base) return cur
        val prefix = base.path + File.separator
        if (!target.path.startsWith(prefix)) return null
        return cur
    }
}
