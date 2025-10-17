package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

/**
 * Small helper classes to build the filter UI used by the extension.
 * Kept minimal and defensive to avoid nulls and unexpected states.
 */

open class QueryFilter(name: String, val key: String, values: Array<String>) :
    AnimeFilter.Select<String>(name, values) {

    /**
     * Returns selected value or empty string if nothing selected or index 0 (convention).
     */
    val selected: String
        get() = if (state == 0 || values.isEmpty()) "" else values.getOrNull(state) ?: ""
}

open class TagFilter(val key: String, name: String, state: Boolean = false) :
    AnimeFilter.CheckBox(name, state)

class GenreFilter(values: Array<String>) :
    QueryFilter(
        "Video Type",
        "genre",
        values.ifEmpty { arrayOf("All", "R-18", "Short Anime", "Motion Anime") },
    )

class SortFilter(values: Array<String>) :
    QueryFilter(
        "Sort By",
        "sort",
        values.ifEmpty { arrayOf("Latest Release", "Latest Upload", "Daily Ranking", "Weekly Ranking", "Monthly Ranking") },
    )

object HotFilter : TagFilter("sort", "Weekly Ranking", true)

class YearFilter(values: Array<String>) :
    QueryFilter("Release Year", "year", values.ifEmpty { arrayOf("All Years") })

class MonthFilter(values: Array<String>) :
    QueryFilter("Release Month", "month", values.ifEmpty { arrayOf("All Months") })

class DateFilter(yearFilter: YearFilter, monthFilter: MonthFilter) :
    AnimeFilter.Group<QueryFilter>("Release Date", listOf(yearFilter, monthFilter))

class CategoryFilter(name: String, filters: List<TagFilter>) :
    AnimeFilter.Group<TagFilter>(name, filters)

class BroadMatchFilter : TagFilter("broad", "Broad Match")

class TagsFilter(filters: List<AnimeFilter<out Any>>) :
    AnimeFilter.Group<AnimeFilter<out Any>>("Tags", filters)
