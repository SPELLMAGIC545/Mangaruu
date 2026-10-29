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
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

@Source
abstract class Mangaruu : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage = listManga("$baseUrl/${if (page > 1) "?page=$page" else ""}")

    override suspend fun getLatestUpdates(page: Int): MangasPage = listManga("$baseUrl/on-going${if (page > 1) "?page=$page" else ""}")

    private class GenreFilter :
        Filter.Select<String>(
            "ジャンル",
            arrayOf("指定なし", "アクション", "冒険", "ホラー", "ロマンス"),
        )

    // KeiSource.getFilterList() is final; custom filter UI cannot override it.
    // Genre selection remains unavailable until supported by KeiSource's API.

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
                    chapter_number = CHAPTER_NUMBER.find(label)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: CHAPTER_URL_NUMBER.find(path)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: -1f
                }
            }
            .distinctBy { it.url }
            .sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.url })
        return SMangaUpdate(manga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val data = client.get(getChapterUrl(chapter)).extractNextJs<ReaderData>()
            ?: error("Mangaruu: chapter metadata not found")
        val config = client.get("$baseUrl/config.json").parseAs<ReaderConfig>()
        val manifest = client.get(config.manifestUrl(data.chapter)).parseAs<ReaderManifest>()
        return data.chapter.imageUrls(manifest).mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder()
        // Match the site's referrerPolicy=no-referrer image requests.
        .removeHeader("Referer")
        .removeHeader("Origin")
        .build()

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

    private fun coverUrl(document: Document): String? = preferredImageUrl(document.selectFirst("main img, article img, img[src*='cover'], img[data-src*='cover']"))

    private fun preferredImageUrl(element: Element?): String? {
        if (element == null) return null
        val attributes = listOf(
            "data-src",
            "data-original",
            "data-lazy-src",
            "data-url",
            "data-image",
            "data-image-url",
            "data-full",
            "src",
        )
        for (attr in attributes) {
            val raw = element.attr(attr).trim()
            if (raw.isBlank() || raw.startsWith("data:") || raw.startsWith("blob:")) continue
            val url = element.absUrl(attr).ifBlank { resolveImageUrl(element, raw) }
            if (validImageUrl(url)) return url
        }
        for (attr in listOf("data-srcset", "srcset")) {
            val candidates = element.attr(attr).split(',')
            for (candidate in candidates.asReversed()) {
                val raw = candidate.trim().substringBefore(' ').trim()
                val url = resolveImageUrl(element, raw)
                if (validImageUrl(url)) return url
            }
        }
        return null
    }

    private fun resolveImageUrl(element: Element, raw: String): String = runCatching { element.baseUri().toHttpUrl().resolve(raw)?.toString() }.getOrNull().orEmpty()

    private fun validImageUrl(url: String): Boolean = (url.startsWith("https://") || url.startsWith("http://")) &&
        !url.contains("placeholder", ignoreCase = true) &&
        !url.substringBefore('?').endsWith(".svg", ignoreCase = true)

    private fun String.toHttpUrlOrNullSafe(): HttpUrl? = runCatching { this.toHttpUrl() }.getOrNull()

    companion object {
        private val CHAPTER_NUMBER = Regex("""(\d+(?:\.\d+)?)\s*話""")
        private val CHAPTER_URL_NUMBER = Regex("""-ch-(\d+(?:[.-]\d+)?)(?:-id-|$)""", RegexOption.IGNORE_CASE)
    }
}
