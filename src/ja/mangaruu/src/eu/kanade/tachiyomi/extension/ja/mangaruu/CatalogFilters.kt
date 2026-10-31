package eu.kanade.tachiyomi.extension.ja.mangaruu

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

internal fun catalogFilters(data: JsonElement?): FilterList {
    val genres = data?.parseAs<List<CatalogGenre>>().orEmpty().filter { it.name.isNotBlank() && it.total > 0 }
    return FilterList(
        buildList {
            add(Filter.Header("作品名・別名・作者で検索"))
            add(StatusFilter())
            add(FormatFilter())
            add(OrderFilter())
            if (genres.isNotEmpty()) {
                add(Filter.Separator())
                add(Filter.Header("含めるジャンルはどれか一致／除外を優先"))
                add(GenreFilter(genres.map { GenreState(it.name, it.id) }))
            }
        },
    )
}

@Serializable
internal class CatalogGenre(val id: Int, val name: String = "", val total: Int = 0)

internal class StatusFilter : Filter.Select<String>("連載状況", arrayOf("すべて", "連載中", "完結")) {
    val value: String get() = arrayOf("", "incomplete", "complete")[state]
}

internal class FormatFilter : Filter.Select<String>("形式", arrayOf("すべて", "横読み", "縦読み")) {
    val value: String get() = arrayOf("", "horizontal", "vertical")[state]
}

internal class OrderFilter : Filter.Select<String>("並び順", arrayOf("更新順", "閲覧数順", "お気に入り数順"))

internal class GenreState(name: String, val id: Int) : Filter.TriState(name)
internal class GenreFilter(genres: List<GenreState>) : Filter.Group<GenreState>("ジャンル", genres)

internal class CatalogCriteria(filters: FilterList) {
    val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.value.orEmpty()
    val format = filters.filterIsInstance<FormatFilter>().firstOrNull()?.value.orEmpty()
    val order = filters.filterIsInstance<OrderFilter>().firstOrNull()?.state ?: 0
    private val genres = filters.filterIsInstance<GenreFilter>().firstOrNull()?.state.orEmpty()
    val include = genres.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.id }.toSet()
    val exclude = genres.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.id }.toSet()
}
