package eu.kanade.tachiyomi.extension.ja.mangaruu

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.Normalizer

internal fun catalogUrl(baseUrl: String, path: String, page: Int): String = "$baseUrl$path${if (page > 1) "/page/$page" else ""}"

@Serializable
internal class CatalogConfig(private val urlThumbnail: String) {
    fun cover(image: String): String = urlThumbnail.toHttpUrl().newBuilder().addPathSegment(image).build().toString()
}

@Serializable
internal class CatalogData(private val results: List<CatalogManga>, private val pagination: CatalogPagination) {
    fun toPage(config: CatalogConfig): CatalogPage = CatalogPage(
        results.map { it.toEntry(config) },
        pagination.hasNextPage(),
    )
}

@Serializable
internal class CatalogPagination(
    @SerialName("current_page") private val currentPage: Int,
    @SerialName("total_page") private val totalPage: Int,
) {
    fun hasNextPage(): Boolean = currentPage < totalPage
}

@Serializable
internal class CatalogPart(val list: List<CatalogManga>)

@Serializable
internal class CatalogManga(
    val slug: String,
    private val name: String,
    private val image: String? = null,
    @SerialName("alt_names") private val altNames: String? = null,
    private val author: String? = null,
    private val genres: List<Int> = emptyList(),
    private val type: String = "",
    private val mode: String = "",
    private val views: Long = 0,
    @SerialName("number_bookmark") private val bookmarks: Int = 0,
    @SerialName("c_published") private val published: String? = null,
    private val dcma: String = "no",
) {
    fun toEntry(config: CatalogConfig): CatalogEntry = CatalogEntry("/m/$slug", name, image?.let(config::cover))

    fun matches(query: String, filters: CatalogCriteria): Boolean = dcma == "no" &&
        (query.isEmpty() || listOf(name, altNames.orEmpty(), author.orEmpty()).any { it.normalized().contains(query) }) &&
        (filters.status.isEmpty() || type == filters.status) &&
        (filters.format.isEmpty() || mode == filters.format) &&
        (filters.include.isEmpty() || genres.any { it in filters.include }) &&
        genres.none { it in filters.exclude }

    fun sortValue(order: Int): Long = when (order) {
        1 -> views
        2 -> bookmarks.toLong()
        else -> 0L
    }

    fun updated(): String = published.orEmpty()
}

internal fun List<CatalogManga>.search(query: String, filters: FilterList, page: Int, config: CatalogConfig): CatalogPage {
    val criteria = CatalogCriteria(filters)
    val matched = filter { it.matches(query.trim().normalized(), criteria) }
    val sorted = if (criteria.order == 0) matched.sortedByDescending { it.updated() } else matched.sortedByDescending { it.sortValue(criteria.order) }
    val start = (page - 1) * 30
    return CatalogPage(sorted.drop(start).take(30).map { it.toEntry(config) }, start + 30 < sorted.size)
}

private fun String.normalized(): String = Normalizer.normalize(this, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)

internal class CatalogEntry(val url: String, val title: String, val thumbnailUrl: String?) {
    fun toSManga(): SManga = SManga.create().also { manga ->
        manga.url = url
        manga.title = title
        manga.thumbnail_url = thumbnailUrl
    }
}

internal class CatalogPage(val mangas: List<CatalogEntry>, val hasNextPage: Boolean) {
    fun toMangasPage(): MangasPage = MangasPage(mangas.map { it.toSManga() }, hasNextPage)
}
