package com.kite.zmusic.plugin

/**
 * `betterncm_native.fs.watchDirectory` 的变化集合。
 * 回调参数是被监听的目录和发生变化的文件名，和框架声明一致。
 */
internal object BetterNcmWatch {
    fun changed(before: Map<String, Long>, after: Map<String, Long>): List<String> {
        val names = LinkedHashSet<String>()
        for ((name, time) in after) {
            val previous = before[name]
            if (previous == null || previous != time) names.add(name)
        }
        for (name in before.keys) {
            if (name !in after) names.add(name)
        }
        return names.toList()
    }
}

internal object BetterNcmFonts {
    fun names(files: List<String>): List<String> =
        files.map { file ->
            val slash = file.replace('\\', '/').substringAfterLast('/')
            val dot = slash.lastIndexOf('.')
            if (dot <= 0) slash else slash.substring(0, dot)
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
}
