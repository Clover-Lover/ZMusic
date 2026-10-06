package com.kite.zmusic.workshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopCategoryTest {
    @Test
    fun allCategoryKeepsEveryCard() {
        assertTrue(WorkshopCategories.matches(card(""), WorkshopCategories.ALL))
        assertTrue(WorkshopCategories.matches(card("betterncm"), WorkshopCategories.ALL))
    }

    @Test
    fun betterNcmCategoryKeepsOnlyTaggedCards() {
        assertTrue(WorkshopCategories.matches(card("betterncm"), WorkshopCategories.BETTERNCM))
        assertTrue(WorkshopCategories.matches(card("BetterNCM"), WorkshopCategories.BETTERNCM))
        assertFalse(WorkshopCategories.matches(card(""), WorkshopCategories.BETTERNCM))
        assertFalse(WorkshopCategories.matches(card("theme"), WorkshopCategories.BETTERNCM))
    }

    @Test
    fun marketCatalogResolvesAgainstTheBaseUrl() {
        val text = """
            [
              {
                "name": "示例",
                "author": "甲",
                "version": "1.2.3",
                "description": "说明",
                "slug": "demo",
                "preview": "previews/demo.png",
                "file-url": "plugins/demo.plugin",
                "repo": "BetterNCM/demo",
                "stars": 4,
                "update_time": 1700000000
              },
              {"name": "缺 slug"},
              {"name": "隐藏", "slug": "hidden", "hide": true}
            ]
        """.trimIndent()
        val parsed = BetterNcmMarket.parse(text, BetterNcmMarket.BASE_URL)
        assertEquals(1, parsed.size)
        val remote = parsed[0]
        assertEquals("demo", remote.card.id)
        assertEquals(WorkshopCategories.BETTERNCM, remote.card.category)
        assertEquals("1.2.3", remote.card.versionLabel)
        assertEquals(
            BetterNcmMarket.BASE_URL + "previews/demo.png",
            remote.card.coverUrl,
        )
        assertEquals(BetterNcmMarket.BASE_URL + "plugins/demo.plugin", remote.fileUrl)
        assertEquals(1_700_000_000_000L, remote.card.updatedAt)
        val first = BetterNcmMarket.page(parsed, page = 1, perPage = 20, q = "示例")
        assertEquals(listOf("demo"), first.entries.map { it.id })
        val none = BetterNcmMarket.page(parsed, page = 1, perPage = 20, q = "没有")
        assertTrue(none.entries.isEmpty())
        assertEquals(remote.fileUrl, remote.toDetail().packageUrl)
        assertTrue(remote.toDetail("# 正文").readme.startsWith("# 正文"))
        assertTrue(remote.toDetail().readme.contains("https://github.com/BetterNCM/demo"))
        assertEquals(
            listOf(
                "https://cdn.jsdelivr.net/gh/BetterNCM/demo@master/README.md",
                "https://cdn.jsdelivr.net/gh/BetterNCM/demo@main/README.md",
            ),
            BetterNcmMarket.readmeCandidates("BetterNCM/demo"),
        )
        assertTrue(BetterNcmMarket.readmeCandidates("not a repo").isEmpty())
        val custom = BetterNcmMarket.catalogSources("https://example.com/market")
        assertEquals("https://example.com/market/", custom.first())
        assertTrue(custom.any { it == BetterNcmMarket.JSDELIVR_URL })
        val defaults = BetterNcmMarket.catalogSources(BetterNcmMarket.BASE_URL)
        assertEquals(BetterNcmMarket.JSDELIVR_URL, defaults[1])
        assertEquals(3, defaults.size)
    }

    private fun card(category: String) = WorkshopPluginCard(
        id = "com.example.demo",
        name = "示例",
        version = 1,
        description = "",
        coverUrl = "",
        author = "",
        publisherUid = "",
        ratingAvg = 0.0,
        ratingCount = 0,
        downloads = 0,
        updatedAt = 0L,
        engineMin = 1,
        engineMax = null,
        category = category,
    )
}
