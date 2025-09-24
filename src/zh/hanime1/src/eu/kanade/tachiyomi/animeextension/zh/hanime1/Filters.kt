package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

open class QueryFilter(name: String, val key: String, values: Array<String>) :
    AnimeFilter.Select<String>(GoogleTranslator.translate(name), GoogleTranslator.translateList(values)) {
    val selected: String
        get() = if (state == 0) "" else values[state]
}

open class TagFilter(val key: String, name: String, state: Boolean = false) :
    AnimeFilter.CheckBox(GoogleTranslator.translate(name), state)

class GenreFilter(values: Array<String>) :
    QueryFilter(
        "影片類型",
        "genre",
        GoogleTranslator.translateList(values.ifEmpty { arrayOf("全部", "裏番", "泡面番", "Motion Anime") }),
    )

class SortFilter(values: Array<String>) :
    QueryFilter(
        "排序方式",
        "sort",
        GoogleTranslator.translateList(values.ifEmpty { arrayOf("最新上市", "最新上傳", "本日排行", "本週排行", "本月排行") }),
    )

object HotFilter : TagFilter("sort", GoogleTranslator.translate("本週排行"), true)

class YearFilter(values: Array<String>) :
    QueryFilter("發佈年份", "year", GoogleTranslator.translateList(values.ifEmpty { arrayOf("全部年份") }))

class MonthFilter(values: Array<String>) :
    QueryFilter("發佈月份", "month", GoogleTranslator.translateList(values.ifEmpty { arrayOf("全部月份") }))

class DateFilter(yearFilter: YearFilter, monthFilter: MonthFilter) :
    AnimeFilter.Group<QueryFilter>(GoogleTranslator.translate("發佈日期"), listOf(yearFilter, monthFilter))

class CategoryFilter(name: String, filters: List<TagFilter>) :
    AnimeFilter.Group<TagFilter>(GoogleTranslator.translate(name), filters)

class BroadMatchFilter : TagFilter("broad", GoogleTranslator.translate("廣泛配對"))

class TagsFilter(filters: List<AnimeFilter<out Any>>) :
    AnimeFilter.Group<AnimeFilter<out Any>>(GoogleTranslator.translate("標籤"), filters)
