package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import kotlinx.coroutines.runBlocking

class QueryFilter(name: String, val key: String, values: Array<String>) :
    AnimeFilter.Select<String>(name, values) {
    val selected: String
        get() = if (state == 0) {
            ""
        } else {
            values[state]
        }
}

class TagFilter(val key: String, name: String, state: Boolean = false) :
    AnimeFilter.CheckBox(name, state)

class GenreFilter(values: Array<String>) :
    QueryFilter(
        runBlocking { translator.translate("影片類型") }, // "Video Type"
        "genre",
        values.ifEmpty { 
            arrayOf(
                runBlocking { translator.translate("全部") }, // "All"
                runBlocking { translator.translate("裏番") }, // "Hentai"
                runBlocking { translator.translate("泡面番") }, // "Short Episodes"
                runBlocking { translator.translate("Motion Anime") } // Stays same
            )
        }
    )

class SortFilter(values: Array<String>) :
    QueryFilter(
        runBlocking { translator.translate("排序方式") }, // "Sort By"
        "sort",
        values.ifEmpty { 
            arrayOf(
                runBlocking { translator.translate("最新上市") }, // "Newest Releases"
                runBlocking { translator.translate("最新上傳") }, // "Recently Added"
                runBlocking { translator.translate("本日排行") }, // "Today's Top"
                runBlocking { translator.translate("本週排行") }, // "Weekly Top"
                runBlocking { translator.translate("本月排行") }  // "Monthly Top"
            )
        }
    )

object HotFilter : TagFilter("sort", runBlocking { translator.translate("本週排行") }, true) // "Weekly Top"

class YearFilter(values: Array<String>) :
    QueryFilter(
        runBlocking { translator.translate("發佈年份") }, // "Release Year"
        "year", 
        values.ifEmpty { 
            arrayOf(runBlocking { translator.translate("全部年份") }) // "All Years"
        }
    )

class MonthFilter(values: Array<String>) :
    QueryFilter(
        runBlocking { translator.translate("發佈月份") }, // "Release Month"
        "month",
        values.ifEmpty { 
            arrayOf(runBlocking { translator.translate("全部月份") }) // "All Months"
        }
    )

class DateFilter(yearFilter: YearFilter, monthFilter: MonthFilter) :
    AnimeFilter.Group<QueryFilter>(
        runBlocking { translator.translate("發佈日期") }, // "Release Date"
        listOf(yearFilter, monthFilter)
    )

class CategoryFilter(name: String, filters: List<TagFilter>) :
    AnimeFilter.Group<TagFilter>(name, filters)

class BroadMatchFilter : TagFilter("broad", runBlocking { translator.translate("廣泛配對") }) // "Broad Match"

class TagsFilter(filters: List<AnimeFilter<out Any>>) :
    AnimeFilter.Group<AnimeFilter<out Any>>(
        runBlocking { translator.translate("標籤") }, // "Tags"
        filters
    )

// ML Kit Translator instance (must be initialized in your main extension class)
private val translator by lazy { ChineseTranslator(context) }
