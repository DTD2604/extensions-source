package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun getFilters(defaultSort: String?) = FilterList(
    Filter.Header("Separate tags with commas (,)"),
    Filter.Header("Prepend with dash (-) to exclude"),
    TagFilter(),
    CategoryFilter(),
    GroupFilter(),
    ArtistFilter(),
    ParodyFilter(),
    CharactersFilter(),
    Filter.Header("Uploaded valid units are h, d, w, m, y."),
    Filter.Header("example: (>20d)"),
    UploadedFilter(),
    Filter.Header("Filter by pages, for example: (>20)"),
    PagesFilter(),

    Filter.Separator(),
    SortFilter(SORT_OPTIONS.indexOfFirst { it.second == defaultSort }.coerceAtLeast(0)),
    OffsetPageFilter(),
    Filter.Header("Sort is ignored if favorites only"),
    FavoriteFilter(),
)

fun FilterList.combineQuery(): String = buildString {
    filterIsInstance<AdvSearchEntryFilter>().forEach { filter ->
        filter.state.split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { tag ->
                val isQuoted = filter.queryKey != "pages" && filter.queryKey != "uploaded"
                if (tag.startsWith("-")) append("-")
                append(filter.queryKey, ':')
                val cleanTag = tag.removePrefix("-").trim().removeSurrounding("\"")
                if (isQuoted) {
                    append('"')
                    append(cleanTag)
                    append('"')
                } else {
                    append(cleanTag)
                }
                append(" ")
            }
    }
}

class TagFilter : AdvSearchEntryFilter("Tags", "tag")
class CategoryFilter : AdvSearchEntryFilter("Categories", "category")
class GroupFilter : AdvSearchEntryFilter("Groups", "group")
class ArtistFilter : AdvSearchEntryFilter("Artists", "artist")
class ParodyFilter : AdvSearchEntryFilter("Parodies", "parody")
class CharactersFilter : AdvSearchEntryFilter("Characters", "character")
class UploadedFilter : AdvSearchEntryFilter("Uploaded", "uploaded")
class PagesFilter : AdvSearchEntryFilter("Pages", "pages")
open class AdvSearchEntryFilter(displayName: String, val queryKey: String) : Filter.Text(displayName)

class OffsetPageFilter : Filter.Text("Offset results by # pages")

class FavoriteFilter : Filter.CheckBox("Show favorites only", false)

class SortFilter(default: Int) : UriPartFilter("Sort By", SORT_OPTIONS, default)

open class UriPartFilter(
    displayName: String,
    val vals: Array<Pair<String, String>>,
    state: Int,
) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
    fun toUriPart() = vals[state].second
}

val SORT_OPTIONS = arrayOf(
    Pair("Popular: All Time", "popular"),
    Pair("Popular: Month", "popular-month"),
    Pair("Popular: Week", "popular-week"),
    Pair("Popular: Today", "popular-today"),
    Pair("Recent", "date"),
)
