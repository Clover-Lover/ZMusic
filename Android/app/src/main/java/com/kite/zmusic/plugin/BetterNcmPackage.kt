package com.kite.zmusic.plugin

import java.io.File
import java.util.zip.ZipFile
import com.kite.zmusic.i18n.t

/**
 * BetterNCM 的 `.plugin` 是带 `manifest.json` 的压缩包，不是带签名的 `.zpp`。
 * 允许主题用的 css / html / 字体，拒绝可执行文件。
 */
internal object BetterNcmPackage {
    private val extraExtensions = setOf(
        "css", "less", "scss", "html", "htm", "map",
        "woff", "woff2", "ttf", "otf",
    )

    private val slugOk = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")

    fun unpack(pkg: File, destDir: File): BetterNcmUnpack {
        if (!pkg.isFile) return BetterNcmUnpack.Invalid(t("不是文件"))
        destDir.mkdirs()
        return try {
            ZipFile(pkg).use { zip -> unpackZip(zip, destDir) }
        } catch (_: Exception) {
            destDir.deleteRecursively()
            BetterNcmUnpack.Invalid(t("无法打开归档"))
        }
    }

    private fun unpackZip(zip: ZipFile, destDir: File): BetterNcmUnpack {
        val destCanon = destDir.canonicalFile
        val raw = zip.entries().toList().filterNot { it.name.startsWith("__MACOSX/") || it.name.startsWith(".") }
        if (raw.isEmpty()) return BetterNcmUnpack.Invalid(t("空包"))
        for (entry in raw) {
            val rel = entry.name.trimStart('/')
            if (rel.isEmpty()) continue
            val directory = entry.isDirectory || rel.endsWith('/')
            if (!entryOk(rel, directory)) {
                destDir.deleteRecursively()
                return BetterNcmUnpack.Invalid(t("非法路径或扩展名: %s", rel))
            }
            val out = File(destDir, rel.removeSuffix("/")).canonicalFile
            if (!out.path.startsWith(destCanon.path + File.separator) && out != destCanon) {
                destDir.deleteRecursively()
                return BetterNcmUnpack.Invalid(t("路径穿越"))
            }
            if (directory) {
                out.mkdirs()
                continue
            }
            out.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val manifest = BetterNcmManifests.read(File(destDir, "manifest.json"))
            ?: return invalid(destDir, t("缺少 manifest.json"))
        if (!slugOk.matches(manifest.slug)) return invalid(destDir, t("插件 id 不合法"))
        if (manifest.slug == PluginDebugProbe.ID) return invalid(destDir, t("不能覆盖内置探针"))
        if (BetterNcmLibs.isModule("betterncm.lib.${manifest.slug}")) {
            return invalid(destDir, t("这个依赖已经内置"))
        }
        val entry = manifest.mainInjects().firstOrNull()
            ?: return invalid(destDir, t("缺少入口"))
        if (!File(destDir, entry).isFile) return invalid(destDir, t("缺少入口"))
        return BetterNcmUnpack.Ok(manifest, entry)
    }

    private fun invalid(destDir: File, reason: String): BetterNcmUnpack.Invalid {
        destDir.deleteRecursively()
        return BetterNcmUnpack.Invalid(reason)
    }

    private fun entryOk(relative: String, directory: Boolean): Boolean {
        if (relative.contains('\\') || relative.startsWith('/') || relative.contains('\u0000')) return false
        val parts = relative.removeSuffix("/").split('/')
        if (parts.any { it == ".." || it.isEmpty() }) return false
        if (directory) return true
        if (PluginPackageRules.zipEntryOk(relative, false)) return true
        val ext = PluginPackageRules.extensionOf(relative)?.lowercase() ?: return false
        return ext in extraExtensions
    }
}

internal sealed class BetterNcmUnpack {
    data class Ok(val manifest: BetterNcmManifest, val entry: String) : BetterNcmUnpack()
    data class Invalid(val reason: String) : BetterNcmUnpack()
}
