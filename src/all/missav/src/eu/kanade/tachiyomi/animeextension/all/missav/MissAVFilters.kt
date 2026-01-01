package eu.kanade.tachiyomi.animeextension.all.missav

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

class SortFilter : AnimeFilter.Select<String>(
    "Sort by",
    arrayOf(
        "Release date",
        "Recent update",
        "Today views",
        "Weekly views",
        "Monthly views",
        "Total views",
    ),
) {
    companion object {
        val SORT = listOf(
            Pair("Release date", "released_at"),
            Pair("Recent update", "published_at"),
            Pair("Today views", "today_views"),
            Pair("Weekly views", "weekly_views"),
            Pair("Monthly views", "monthly_views"),
            Pair("Total views", "views"),
        )
    }
}

class GenreList : AnimeFilter.Select<String>(
    "Genres (Single)",
    arrayOf(
        "",
        "Uncensored Leak",
        "Hd",
        "Exclusive",
        "Creampie",
        "Big Breasts",
        "Individual",
        "Wife",
        "Mature Woman",
        "Ordinary Person",
    ),
) {
    companion object {
        val GENRES = listOf(
            Pair("", ""),
            Pair("Uncensored Leak", "en/uncensored-leak"),
            Pair("Hd", "en/genres/Hd"),
            Pair("Exclusive", "en/genres/Exclusive"),
            Pair("Creampie", "en/genres/Creampie"),
            Pair("Big Breasts", "en/genres/Big%20Breasts"),
            Pair("Individual", "en/genres/Individual"),
            Pair("Wife", "en/genres/Wife"),
            Pair("Mature Woman", "en/genres/Mature%20Woman"),
            Pair("Ordinary Person", "en/genres/Ordinary%20Person"),
        )
    }
}

// Custom TriState implementation
class TriFilterVal(name: String) : AnimeFilter.TriState(name)

class MultiGenreFilter : AnimeFilter.Group<AnimeFilter.TriState>(
    "Genres (Multi-select)",
    listOf(
        TriFilterVal("Uncensored Leak"),
        TriFilterVal("Hd"),
        TriFilterVal("Exclusive"),
        TriFilterVal("Creampie"),
        TriFilterVal("Big Breasts"),
        TriFilterVal("Individual"),
        TriFilterVal("Wife"),
        TriFilterVal("Mature Woman"),
        TriFilterVal("Ordinary Person"),
    )
)

fun getFilters() = AnimeFilterList(
    SortFilter(),
    GenreList(),
    MultiGenreFilter(),
    AnimeFilter.Separator(),
    AnimeFilter.Header("Multi-genre filtering is client-side and may be slow"),
    AnimeFilter.Header("Green ✓ = Include | Red ✗ = Exclude | Empty = Ignore"),
)

data class FilterSearchParams(
    val genres: List<String> = emptyList(),
    val blacklisted: List<String> = emptyList(),
)

internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
    if (filters.isEmpty()) return FilterSearchParams()

    val multiGenreFilter = filters.getOrNull(2) as? MultiGenreFilter

    if (multiGenreFilter != null) {
        val included = mutableListOf<String>()
        val excluded = mutableListOf<String>()

        multiGenreFilter.state.forEach { triState ->
            val genreName = triState.name
            when (triState.state) {
                AnimeFilter.TriState.STATE_INCLUDE -> included.add(genreName)
                AnimeFilter.TriState.STATE_EXCLUDE -> excluded.add(genreName)
                else -> {}
            }
        }

        return FilterSearchParams(included, excluded)
    }

    return FilterSearchParams()
}
