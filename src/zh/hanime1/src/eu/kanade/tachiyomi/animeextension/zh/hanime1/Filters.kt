package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

open class QueryFilter(
    name: String,
    val key: String,
    values: Array<String>,
) : AnimeFilter.Select<String>(name, values) {

    val selected: String
        get() = if (state == 0) "" else values[state]
}

open class TagFilter(
    val key: String,
    name: String,
    state: Boolean = false,
) : AnimeFilter.CheckBox(name, state)

class GenreFilter(
    values: Array<String>,
) : QueryFilter(
    "Video Type",
    "genre",
    if (values.isNotEmpty()) values else arrayOf("All", "R-18", "Short Anime", "Motion Anime"),
)

class SortFilter(
    values: Array<String>,
) : QueryFilter(
    "Sort By",
    "sort",
    if (values.isNotEmpty()) values else arrayOf(
        "Latest Release",
        "Latest Upload",
        "Daily Ranking",
        "Weekly Ranking",
        "Monthly Ranking",
    ),
)

class HotFilter : TagFilter("sort", "Weekly Ranking", true)

class YearFilter(
    values: Array<String>,
) : QueryFilter(
    "Release Year",
    "year",
    if (values.isNotEmpty()) values else arrayOf("All Years"),
)

class MonthFilter(
    values: Array<String>,
) : QueryFilter(
    "Release Month",
    "month",
    if (values.isNotEmpty()) values else arrayOf("All Months"),
)

class DateFilter(
    yearFilter: YearFilter,
    monthFilter: MonthFilter,
) : AnimeFilter.Group<QueryFilter>("Release Date", listOf(yearFilter, monthFilter))

class CategoryFilter(
    name: String,
    filters: List<TagFilter>,
) : AnimeFilter.Group<TagFilter>(name, filters)

class BroadMatchFilter : TagFilter("broad", "Broad Match")

class TagsFilter(
    filters: List<AnimeFilter<*>>,
) : AnimeFilter.Group<AnimeFilter<*>>("Tags", filters)
