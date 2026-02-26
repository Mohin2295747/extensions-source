package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

abstract class QueryFilter(
    name: String,
    val key: String,
    private val displayValues: Array<String>,
    private val originalValues: Array<String>,
) : AnimeFilter.Select<String>(name, displayValues) {
    val selected: String
        get() = if (state == 0) "" else originalValues[state]

    val selectedDisplay: String
        get() = if (state == 0) "" else displayValues[state]
}

class GenreFilter(values: Array<String>) : QueryFilter(
    "Genre",
    "genre",
    if (values.isNotEmpty()) {
        values.map { Tags.getTranslatedGenre(it) }.toTypedArray()
    } else {
        arrayOf("All", "Hentai", "Short Anime", "Motion Anime", "3DCG", "2.5D", "2D Animation", "AI Generated", "MMD", "Cosplay")
    },
    if (values.isNotEmpty()) {
        values
    } else {
        arrayOf("", "裏番", "泡麵番", "Motion Anime", "3DCG", "2.5D", "2D動畫", "AI生成", "MMD", "Cosplay")
    },
)

class SortFilter(values: Array<String>) : QueryFilter(
    "Sort by",
    "sort",
    if (values.isNotEmpty()) {
        values.map { Tags.getTranslatedSort(it) }.toTypedArray()
    } else {
        arrayOf("Newest", "Latest Upload", "Daily Ranking", "Weekly Ranking", "Monthly Ranking", "Most Views", "Highest Rating", "Longest Duration", "Popular Now")
    },
    if (values.isNotEmpty()) {
        values
    } else {
        arrayOf("", "最新上傳", "本日排行", "本週排行", "本月排行", "觀看次數", "讚好比例", "時長最長", "他們在看")
    },
)

class HotFilter : AnimeFilter.CheckBox("Weekly Ranking", true)

class YearFilter(values: Array<String>) : QueryFilter(
    "Release Year",
    "year",
    if (values.isNotEmpty()) {
        values.map { Tags.getTranslatedYear(it) }.toTypedArray()
    } else {
        arrayOf("All Years")
    },
    if (values.isNotEmpty()) {
        values
    } else {
        arrayOf("all-years")
    },
)

class MonthFilter(values: Array<String>) : QueryFilter(
    "Release Month",
    "month",
    if (values.isNotEmpty()) {
        values.map { Tags.getTranslatedMonth(it) }.toTypedArray()
    } else {
        arrayOf("All Months")
    },
    if (values.isNotEmpty()) {
        values
    } else {
        arrayOf("all-months")
    },
)

class DateFilter(yearFilter: YearFilter, monthFilter: MonthFilter) :
    AnimeFilter.Group<QueryFilter>("Release Date", listOf(yearFilter, monthFilter))

class BroadMatchFilter : AnimeFilter.CheckBox("Broad Match", false)

class TagFilter(val key: String, name: String, val originalValue: String) : AnimeFilter.CheckBox(name, false)

class CategoryFilter(name: String, filters: List<TagFilter>) :
    AnimeFilter.Group<TagFilter>(name, filters)

class TagsFilter(filters: List<AnimeFilter<out Any>>) :
    AnimeFilter.Group<AnimeFilter<out Any>>("Tags", filters)
