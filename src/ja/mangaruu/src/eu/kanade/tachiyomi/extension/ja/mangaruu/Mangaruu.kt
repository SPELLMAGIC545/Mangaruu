package eu.kanade.tachiyomi.extension.ja.mangaruu

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Mangaruu : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(Canva2ImageInterceptor())

    override suspend fun getPopularManga(page: Int): MangasPage = listManga(catalogUrl(baseUrl, "", page))

    override suspend fun getLatestUpdates(page: Int): MangasPage = listManga(catalogUrl(baseUrl, "/on-going", page))

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/genres.json").parseAs()

    override fun getFilterList(data: JsonElement?): FilterList = catalogFilters(data)

    private val searchMutex = Mutex()
    private var searchCatalog: List<CatalogManga> = emptyList()
    private var searchLoadedAt = 0L

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = searchMutex.withLock {
        val now = System.currentTimeMillis()
        if (searchCatalog.isEmpty() || now - searchLoadedAt > 10 * 60 * 1000) {
            val entries = mutableListOf<CatalogManga>()
            var part = 1
            while (true) {
                val items = client.get("$baseUrl/mangas_$part.json", ensureSuccess = false).use { response ->
                    // The site's own catalog loader ends at the first missing part.
                    if (response.code == 404 && part > 1) return@use null
                    check(response.isSuccessful) { "Mangaruu: catalog HTTP ${response.code}" }
                    response.parseAs<CatalogPart>().list
                } ?: break
                if (items.isEmpty()) break
                entries.addAll(items)
                part++
            }
            searchCatalog = entries.distinctBy { it.slug }
            searchLoadedAt = System.currentTimeMillis()
        }
        val config = client.get("$baseUrl/config.json").parseAs<CatalogConfig>()
        searchCatalog.search(query, filters, page, config).toMangasPage()
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
                val info = ChapterLabel.parse(label, path)
                SChapter.create().apply {
                    url = path
                    name = info.name
                    chapter_number = info.number
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
        val data = client.get(url).extractNextJs<CatalogData>()
            ?: error("Mangaruu: catalog metadata not found")
        val config = client.get("$baseUrl/config.json").parseAs<CatalogConfig>()
        return data.toPage(config).toMangasPage()
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
}
