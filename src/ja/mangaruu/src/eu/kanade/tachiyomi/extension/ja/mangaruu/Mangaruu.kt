package eu.kanade.tachiyomi.extension.ja.mangaruu

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Unofficial, experimental Mangaruu source for Mihon/Tachiyomi forks.
 * No DRM, login, payment or anti-bot bypass is performed.
 *
 * Confirmed URL formats (October 2026):
 *   /m/<slug>-<id>      manga detail
 *   /c/<slug>-ch-...    chapter reader
 * Known limitation: chapter image delivery may be JavaScript-only.
 */
@Source
abstract class Mangaruu : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage =
        listManga("$baseUrl/${if (page > 1) "?page=$page" else ""}")

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        listManga("$baseUrl/on-going${if (page > 1) "?page=$page" else ""}")

    private class GenreFilter : Filter.Select<String>(
        "ジャンル",
        arrayOf("指定なし", "アクション", "冒険", "ホラー", "ロマンス"),
    )

    override fun getFilterList(): FilterList = FilterList(GenreFilter())

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.state ?: 0
        val slugs = listOf("", "akushon", "bouken", "horaa", "romansu")
        val keyword = query.trim()
        if (genre > 0 && keyword.isBlank()) {
            val slug = slugs.getOrNull(genre) ?: return MangasPage(emptyList(), false)
            val suffix = if (page > 1) "/page/$page" else ""
            return listManga("$baseUrl/genre/$slug$suffix")
        }
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val suffix = if (page > 1) "&page=$page" else ""
        val document = client.get("$baseUrl/search?q=$encoded$suffix").asJsoup()
        val results = mangaListFrom(document)
        // Combined genre + text search is not exposed by a confirmed site endpoint.
        // Do not silently pretend that the selected genre was applied.
        return results
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || !url.encodedPath.startsWith("/m/")) return null
        val document = client.get(url.toString()).asJsoup()
        val heading = document.selectFirst("h1")?.text()?.trim().orEmpty()
        if (heading.isBlank()) return null
        return SManga.create().apply {
            this.url = url.encodedPath
            title = heading
            thumbnail_url = coverUrl(document)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val heading = document.selectFirst("h1")?.text()?.trim()
        if (!heading.isNullOrBlank()) manga.title = heading
        coverUrl(document)?.let { manga.thumbnail_url = it }
        manga.status = when {
            document.text().contains("完結") -> SManga.COMPLETED
            document.text().contains("連載中") -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        // Chapter links are present in the HTML of observed /m/... pages.
        // Avoid duplicate links: pages often render chapter lists twice.
        val chapterList = document.select("a[href^=/c/], a[href*='mangaruu.com/c/']")
            .mapNotNull { link ->
                val path = link.absUrl("href").toHttpUrlOrNullSafe()?.encodedPath ?: return@mapNotNull null
                if (!path.startsWith("/c/")) return@mapNotNull null
                val label = link.text().trim().ifBlank { path.substringAfterLast('/') }
                SChapter.create().apply {
                    url = path
                    name = label
                    chapter_number = CHAPTER_NUMBER.find(label)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                }
            }
            .distinctBy { it.url }
            .sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.url })
        return SMangaUpdate(manga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val images = extractPages(document)
        return images.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private suspend fun listManga(url: String): MangasPage {
        val document = client.get(url).asJsoup()
        return mangaListFrom(document)
    }

    private fun mangaListFrom(document: Document): MangasPage {
        val entries = document.select("a[href^=/m/], a[href*='mangaruu.com/m/']")
            .mapNotNull { link ->
                val absoluteUrl = link.absUrl("href")
                val path = absoluteUrl.toHttpUrlOrNullSafe()?.encodedPath ?: return@mapNotNull null
                if (!path.startsWith("/m/")) return@mapNotNull null
                val thumb = preferredImageUrl(link.selectFirst("img"))
                val label = link.selectFirst("h2, h3, h4, .title")?.text()
                    ?: link.ownText().ifBlank { link.text() }
                // Exclude navigation links and empty cards.
                if (label.isBlank() && thumb.isNullOrBlank()) return@mapNotNull null
                SManga.create().apply {
                    this.url = path
                    title = label.ifBlank { path.substringAfter("/m/").substringBeforeLast('-').replace('-', ' ') }
                    thumbnail_url = thumb
                }
            }
            .distinctBy { it.url }
        val next = document.selectFirst("a[rel=next], a.next, .pagination a[aria-label=Next]") != null
        return MangasPage(entries, next)
    }

    private fun coverUrl(document: Document): String? =
        preferredImageUrl(document.selectFirst("main img, article img, img[src*='cover'], img[data-src*='cover']"))

    private fun extractPages(document: Document): List<String> {
        // Prefer only reader containers so a logo/cover/related-title thumbnail isn't treated as a page.
        val containers = listOf(
            "#reader", ".reader", ".reading-content", ".chapter-content", ".chapter-images",
            ".viewer", ".page-images", "[id*=reader]", "[class*=reader]",
        )
        for (selector in containers) {
            val els = document.select("$selector img")
            val urls = els.mapNotNull(::preferredImageUrl)
                .filter(::looksLikePageImage)
                .distinct()
            if (urls.isNotEmpty()) return urls
        }

        // Fallback to a main-area image list (exclude common navigation/cover images).
        val mainImages = document.select("main img, article img")
            .mapNotNull(::preferredImageUrl).filter(::looksLikePageImage).distinct()
        if (mainImages.size >= 2) return mainImages

        // JSON-hydrated pages sometimes store page URLs in inline scripts.
        // This is intentionally conservative; it does not execute scripts or bypass site controls.
        val scripts = document.select("script").joinToString("\n") { it.data() }
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
        val arrays = IMAGE_ARRAY.findAll(scripts).flatMap { it.groupValues[1].let(IMAGE_URL::findAll) }
            .map { it.value.trimEnd('\\') }.toList()
        if (arrays.isNotEmpty()) return arrays.filter(::looksLikePageImage).distinct()

        return emptyList()
    }

    private fun preferredImageUrl(element: Element?): String? {
        if (element == null) return null
        for (attr in listOf("data-src", "data-original", "data-lazy-src", "data-url", "src")) {
            val url = element.absUrl(attr).ifBlank { element.attr(attr) }
            if (url.startsWith("https://") || url.startsWith("http://")) {
                if (!url.contains("placeholder", ignoreCase = true) && !url.endsWith(".svg")) return url
            }
        }
        return null
    }

    private fun looksLikePageImage(url: String): Boolean {
        val withoutQuery = url.substringBefore('?').lowercase()
        return withoutQuery.startsWith("http") &&
            !withoutQuery.contains("/logo") &&
            !withoutQuery.contains("/avatar") &&
            !withoutQuery.contains("/icon") &&
            !withoutQuery.contains("/cover") &&
            !withoutQuery.contains("thumbnail") &&
            !withoutQuery.contains("/banner")
    }

    private fun String.toHttpUrlOrNullSafe(): HttpUrl? =
        runCatching { this.toHttpUrl() }.getOrNull()

    companion object {
        private val CHAPTER_NUMBER = Regex("""(\d+(?:\.\d+)?)\s*話""")
        private val IMAGE_ARRAY = Regex(
            """(?is)(?:["']?(?:images|pages|imageUrls|image_urls|chapterImages|chapter_images)["']?\s*[:=]\s*)(\[[\s\S]*?])""",
        )
        private val IMAGE_URL = Regex(
            """https?://[^\s"'\\<>]+?\.(?:jpe?g|png|webp|avif)(?:\?[^\s"'\\<>]*)?""",
            RegexOption.IGNORE_CASE,
        )
    }
}
