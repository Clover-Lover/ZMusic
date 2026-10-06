package com.kite.zmusic.workshop

import com.kite.zmusic.plugin.PluginEngine
import com.kite.zmusic.plugin.PluginRecord
import com.kite.zmusic.plugin.PluginRegisterResult
import com.kite.zmusic.ui.notice.IslandNoticeCenter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kite.zmusic.i18n.t

class WorkshopRepository(
    private val client: WorkshopClient,
    private val downloader: WorkshopDownloader,
    private val pluginEngine: PluginEngine,
    private val auth: WorkshopAuthStore,
    private val notices: IslandNoticeCenter,
    private val cacheDir: File,
) {
    fun hasSession(): Boolean = auth.hasToken()

    fun session() = auth.current()

    suspend fun listPlugins(
        page: Int,
        q: String = "",
        category: String = "",
        perPage: Int = 20,
        refresh: Boolean = false,
    ): WorkshopPage<WorkshopPluginCard> {
        if (category.equals(WorkshopCategories.BETTERNCM, ignoreCase = true)) {
            return client.listBetterNcm(page, perPage = perPage, q = q, refresh = refresh)
        }
        return client.listPlugins(page = page, perPage = perPage, q = q, category = category)
    }

    suspend fun detail(id: String): WorkshopPluginDetail {
        if (!id.contains('.')) {
            client.findBetterNcm(id)?.let { return it.toDetail() }
        }
        return client.pluginDetail(id)
    }

    suspend fun betterNcmReadme(id: String): String {
        val remote = client.findBetterNcm(id) ?: return ""
        return remote.toDetail(client.fetchRepoReadme(remote.repo)).readme
    }

    suspend fun rate(id: String, stars: Int): WorkshopRatingResult =
        client.putRating(id, stars)

    suspend fun clearRating(id: String): WorkshopRatingResult =
        client.deleteRating(id)

    fun modules(): List<PluginRecord> = pluginEngine.listModules()

    fun findModule(id: String): PluginRecord? =
        pluginEngine.listModules().find { it.id == id }

    fun modulesRevision() = pluginEngine.modulesRevision

    fun setModuleEnabled(id: String, enabled: Boolean) {
        pluginEngine.setModuleEnabled(id, enabled)
    }

    fun uninstallModule(id: String): Boolean = pluginEngine.uninstallModule(id)

    /**
     * 下载 → 严格验签 → 解压注册（默认不启用）。
     * 进度用 sticky 灵动岛（与 App 更新下载同一套），结束再换成短通知。
     */
    suspend fun downloadAndInstall(detail: WorkshopPluginDetail): Result<PluginRecord> =
        withContext(Dispatchers.IO) {
            if (WorkshopCategories.matches(detail.card, WorkshopCategories.BETTERNCM)) {
                val url = detail.packageUrl.trim()
                if (!url.startsWith("http://", ignoreCase = true) &&
                    !url.startsWith("https://", ignoreCase = true)
                ) {
                    notices.show(t("没有可下载的插件包"))
                    return@withContext Result.failure(IllegalStateException("betterncm url"))
                }
                val dest = File(cacheDir, "bncm-${detail.card.id.take(48)}-${detail.card.version}.plugin")
                var finishedOk = false
                try {
                    notices.setSticky(t("正在下载 %s… 0%%", detail.card.name))
                    val file = downloader.downloadPlain(url, dest) { received, total ->
                        val pct = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 100) else 0
                        notices.setSticky(t("正在下载 %s… %s%%", detail.card.name, pct))
                    }.getOrElse { err ->
                        notices.clearSticky()
                        notices.show(t("插件市场暂时打不开"))
                        return@withContext Result.failure(err)
                    }
                    notices.setSticky(t("正在安装 %s…", detail.card.name))
                    val installed = applyInstallResult(pluginEngine.installBetterNcm(file))
                    finishedOk = installed.isSuccess
                    return@withContext installed
                } finally {
                    dest.delete()
                    if (!finishedOk) notices.clearSticky()
                }
            }
            val id = detail.card.id
            val dest = File(cacheDir, "workshop-${id.replace('.', '_')}-${detail.card.version}.zpp")
            var finishedOk = false
            try {
                notices.setSticky(t("正在下载 %s… 0%%", detail.card.name))
                val file = downloader.download(id, detail, dest) { received, total ->
                    val pct = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 100) else 0
                    notices.setSticky(t("正在下载 %s… %s%%", detail.card.name, pct))
                }.getOrElse { err ->
                    handleErr(err)
                    return@withContext Result.failure(err)
                }
                notices.setSticky(t("正在安装 %s…", detail.card.name))
                val installed = applyInstallResult(pluginEngine.installWorkshopZpp(file))
                finishedOk = installed.isSuccess
                installed
            } finally {
                dest.delete()
                if (!finishedOk) notices.clearSticky()
            }
        }

    /**
     * 模块页：从本机 `.zpp` 注册。新装默认不启用。不能覆盖内置探针。
     */
    suspend fun installFromLocalZpp(zpp: File): Result<PluginRecord> =
        withContext(Dispatchers.IO) {
            var finishedOk = false
            try {
                notices.setSticky(t("正在安装…"))
                val installed = applyInstallResult(pluginEngine.installWorkshopZpp(zpp))
                finishedOk = installed.isSuccess
                installed
            } finally {
                if (!finishedOk) notices.clearSticky()
            }
        }

    private fun applyInstallResult(result: PluginRegisterResult): Result<PluginRecord> =
        when (result) {
            is PluginRegisterResult.Installed -> {
                notices.clearSticky()
                notices.show(
                    if (result.record.enabled) {
                        t("已安装并启用「%s」", result.record.name)
                    } else {
                        t("已安装「%s」，默认未启用", result.record.name)
                    },
                )
                Result.success(result.record)
            }
            is PluginRegisterResult.Replaced -> {
                notices.clearSticky()
                notices.show(t("已更新「%s」", result.record.name))
                Result.success(result.record)
            }
            is PluginRegisterResult.Skipped -> {
                notices.clearSticky()
                notices.show(result.reason)
                Result.failure(IllegalStateException(result.reason))
            }
        }

    private fun handleErr(err: Throwable) {
        when (err) {
            is WorkshopApiError.Unauthorized -> notices.show(t("需要重新确认社区身份"))
            is WorkshopApiError.RateLimited -> notices.show(t("请求太频繁，稍后再试"))
            is WorkshopApiError.Missing -> notices.show(t("插件不存在或已下架"))
            else -> notices.show(err.message?.takeIf { it.isNotBlank() } ?: t("下载失败"))
        }
    }
}
