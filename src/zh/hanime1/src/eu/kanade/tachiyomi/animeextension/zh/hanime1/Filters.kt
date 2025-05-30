package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class QueryFilter(
    name: String,
    val key: String,
    values: Array<String>,
) : AnimeFilter.Select<String>(name, values) {
    val selected: String
        get() = if (state == 0) "" else values[state]
}

class TagFilter(
    val key: String,
    name: String,
    state: Boolean = false,
) : AnimeFilter.CheckBox(name, state)

class GenreFilter(
    private val translator: ChineseTranslator,
    values: Array<String> = emptyArray(),
) : QueryFilter(
    name = "影片類型",  // "Video Type"
    key = "genre",
    values = values.ifEmpty {
        arrayOf(
            "全部",    // "All"
            "裏番",    // "Hentai"
            "泡面番",  // "Short Episodes"
            "Motion Anime"
        )
    }
) {
    override val name: String
        get() = translator.translateCached(super.name)

    override val values: Array<String>
        get() = super.values.map { translator.translateCached(it) }.toTypedArray()
}

class SortFilter(
    private val translator: ChineseTranslator,
    values: Array<String> = emptyArray(),
) : QueryFilter(
    name = "排序方式",  // "Sort By"
    key = "sort",
    values = values.ifEmpty {
        arrayOf(
            "最新上市",  // "Newest Releases"
            "最新上傳",  // "Recently Added"
            "本日排行",  // "Today's Top"
            "本週排行",  // "Weekly Top"
            "本月排行"   // "Monthly Top"
        )
    }
) {
    override val name: String
        get() = translator.translateCached(super.name)

    override val values: Array<String>
        get() = super.values.map { translator.translateCached(it) }.toTypedArray()
}

class HotFilter(
    translator: ChineseTranslator,
) : TagFilter(
    key = "sort",
    name = "本週排行",  // "Weekly Top"
    state = true
) {
    private val translatorRef = translator
    
    override val name: String
        get() = translatorRef.translateCached(super.name)
}

class YearFilter(
    private val translator: ChineseTranslator,
    values: Array<String> = emptyArray(),
) : QueryFilter(
    name = "發佈年份",  // "Release Year"
    key = "year",
    values = values.ifEmpty {
        arrayOf("全部年份")  // "All Years"
    }
) {
    override val name: String
        get() = translator.translateCached(super.name)

    override val values: Array<String>
        get() = super.values.map { translator.translateCached(it) }.toTypedArray()
}

class MonthFilter(
    private val translator: ChineseTranslator,
    values: Array<String> = emptyArray(),
) : QueryFilter(
    name = "發佈月份",  // "Release Month"
    key = "month",
    values = values.ifEmpty {
        arrayOf("全部月份")  // "All Months"
    }
) {
    override val name: String
        get() = translator.translateCached(super.name)

    override val values: Array<String>
        get() = super.values.map { translator.translateCached(it) }.toTypedArray()
}

class DateFilter(
    private val translator: ChineseTranslator,
    yearFilter: YearFilter,
    monthFilter: MonthFilter,
) : AnimeFilter.Group<QueryFilter>(
    name = "發佈日期",  // "Release Date"
    filters = listOf(yearFilter, monthFilter)
) {
    override val name: String
        get() = translator.translateCached(super.name)
}
