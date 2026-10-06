package com.kite.zmusic.plugin

/**
 * 沙盒里的一小份命令。没有管道、重定向和额外解释器。
 * 成功是 true，命令不在名单里、路径出了沙盒或目标不对是 false。
 */
internal object BetterNcmExec {
    private val ALLOWED = setOf(
        "ls", "dir", "pwd", "cat", "type", "echo", "mkdir", "touch",
        "cp", "copy", "mv", "move", "rm", "del", "cd",
    )

    fun run(line: String, host: BetterNcmHost): Boolean {
        val tokens = tokenize(line) ?: return false
        if (tokens.isEmpty()) return false
        val cmd = tokens[0].lowercase()
        if (cmd !in ALLOWED) return false
        val args = tokens.drop(1)
        return when (cmd) {
            "pwd", "echo" -> true
            "ls", "dir" -> ls(host, args)
            "cat", "type" -> args.size == 1 && host.exists(args[0]) && host.readDir(args[0]) == null
            "mkdir" -> args.size == 1 && host.mkdir(args[0])
            "touch" -> args.size == 1 && host.writeFile(args[0], ByteArray(0))
            "cd" -> args.size == 1 && host.exists(args[0]) && host.readDir(args[0]) != null
            "rm", "del" -> args.size == 1 && host.remove(args[0])
            "cp", "copy" -> args.size == 2 && copy(host, args[0], args[1])
            "mv", "move" -> args.size == 2 && host.rename(args[0], args[1])
            else -> false
        }
    }

    private fun ls(host: BetterNcmHost, args: List<String>): Boolean {
        if (args.size > 1) return false
        val path = args.firstOrNull() ?: "C:\\"
        if (host.readDir(path) != null) return true
        return host.exists(path)
    }

    private fun copy(host: BetterNcmHost, from: String, to: String): Boolean {
        val bytes = host.readFile(from) ?: return false
        if (!host.exists(from)) return false
        return host.writeFile(to, bytes)
    }

    fun tokenize(line: String): List<String>? {
        if (line.isEmpty() || line.length > 500) return null
        if (line.any { it == '|' || it == '&' || it == ';' || it == '>' || it == '<' || it == '`' || it == '$' || it == '\n' || it == '\r' }) {
            return null
        }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = false
        for (ch in line) {
            when {
                ch == '"' -> quote = !quote
                ch.isWhitespace() && !quote -> {
                    if (cur.isNotEmpty()) {
                        out.add(cur.toString())
                        cur.clear()
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (quote) return null
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
