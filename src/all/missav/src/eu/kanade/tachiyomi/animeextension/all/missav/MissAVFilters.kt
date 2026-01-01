package eu.kanade.tachiyomi.animeextension.all.missav

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

abstract class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : AnimeFilter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
) {
    val selected get() = options[state].second.takeUnless { state == 0 }
}

class SortFilter : SelectFilter(
    "Sort by",
    SORT,
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

class GenreList : SelectFilter(
    "Genres (Single)",
    GENRES,
) {
    companion object {
        val GENRES = listOf(
            Pair("", ""),
            Pair("Uncensored Leak", "en/uncensored-leak"),
            Pair("Hd", "en/genres/Hd"),
            Pair("Exclusive", "en/genres/Exclusive"),
        )
    }
}

class MultiGenreFilter : AnimeFilter.Group<AnimeFilter.CheckBox>("Genres (Multiple)", getGenreCheckboxes()) {
    val selectedGenres: List<String>
        get() = state
            .filter { it.state }
            .map { it.name }
            .filter { it.isNotEmpty() }
    
    companion object {
        private fun getGenreCheckboxes(): List<AnimeFilter.CheckBox> {
            return GenreList.GENRES.map { (name, _) ->
                AnimeFilter.CheckBox(name)
            }
        }
    }
}

class UncensoredFilter : AnimeFilter.CheckBox("Uncensored Only", false)

class ResetAllFilter : AnimeFilter.Header("↺ Reset All Filters")

fun getFilters() = AnimeFilterList(
    ResetAllFilter(),
    SortFilter(),
    UncensoredFilter(),
    GenreList(),
    MultiGenreFilter(),
    AnimeFilter.Separator(),
    AnimeFilter.Header("Note: Multi-genre filtering is done client-side"),
    AnimeFilter.Header("and will be slower but more accurate"),
)