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
) : QueryFilter(
    name = "影片類型",
    key = "genre",
    values = arrayOf(
        "全部",
        "裏番",
        "泡面番",
        "Motion Anime",
    )
) {
    private val translatedName: String by lazy { runBlocking { translator.translate(name) } }
    private val translatedValues: Array<String> by lazy {
        values.map { runBlocking { translator.translate(it) } }.toTypedArray()
    }

    override fun getName(): String = translatedName
    override fun getValues(): Array<String> = translatedValues
}

class SortFilter(
    private val translator: ChineseTranslator,
) : QueryFilter(
    name = "排序方式",
    key = "sort",
    values = arrayOf(
        "最新上市",
        "最新上傳",
        "本日排行",
        "本週排行",
        "本月排行",
    )
) {
    private val translatedName: String by lazy { runBlocking { translator.translate(name) } }
    private val translatedValues: Array<String> by lazy {
        values.map { runBlocking { translator.translate(it) } }.toTypedArray()
    }

    override fun getName(): String = translatedName
    override fun getValues(): Array<String> = translatedValues
}

class HotFilter(
    translator: ChineseTranslator,
) : TagFilter(
    key = "sort",
    name = "本週排行",
    state = true,
) {
    private val translatedName: String by lazy { runBlocking { translator.translate(name) } }
    
    override fun getName(): String = translatedName
}

class YearFilter(
    private val translator: ChineseTranslator,
) : QueryFilter(
    name = "發佈年份",
    key = "year",
    values = arrayOf(
        "全部年份",
    )
) {
    private val translatedName: String by lazy { runBlocking { translator.translate(name) } }
    private val translatedValues: Array<String> by lazy {
        values.map { runBlocking { translator.translate(it) } }.toTypedArray()
    }

    override fun getName(): String = translatedName
    override fun getValues(): Array<String> = translatedValues
}

class MonthFilter(
    private val translator: ChineseTranslator,
) : QueryFilter(
    name = "發佈月份",
    key = "month",
    values = arrayOf(
        "全部月份",
    )
) {
    private val translatedName: String by lazy { runBlocking { translator.translate(name) } }
    private val translatedValues: Array<String> by lazy {
        values.map { runBlocking { translator.translate(it) } }.toTypedArray()
    }

    override fun getName(): String = translatedName
    override fun getValues(): Array<String> = translatedValues
}

class DateFilter(
    private val translator: ChineseTranslator,
    yearFilter: YearFilter,
    monthFilter: MonthFilter,
) : AnimeFilter.Group<QueryFilter>(
    name = "發佈日期",
    filters = listOf(yearFilter, monthFilter),
) {
    private val translatedName: String by lazy { runBlocking { translator.translate(name) } }
    
    override fun getName(): String = translatedName
}
