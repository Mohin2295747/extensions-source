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
        runBlocking { TRANSLATOR.translate("影片類型") }, // "Video Type"
        "genre",
        values.ifEmpty {
            arrayOf(
                runBlocking { TRANSLATOR.translate("全部") }, // "All"
                runBlocking { TRANSLATOR.translate("裏番") }, // "Hentai"
                runBlocking { TRANSLATOR.translate("泡面番") }, // "Short Episodes"
                runBlocking { TRANSLATOR.translate("Motion Anime") }, // Stays same
            ),
        },
    )

class SortFilter(values: Array<String>) :
    QueryFilter(
        runBlocking { TRANSLATOR.translate("排序方式") }, // "Sort By"
        "sort",
        values.ifEmpty {
            arrayOf(
                runBlocking { TRANSLATOR.translate("最新上市") }, // "Newest Releases"
                runBlocking { TRANSLATOR.translate("最新上傳") }, // "Recently Added"
                runBlocking { TRANSLATOR.translate("本日排行") }, // "Today's Top"
                runBlocking { TRANSLATOR.translate("本週排行") }, // "Weekly Top"
                runBlocking { TRANSLATOR.translate("本月排行") }, // "Monthly Top"
            ),
        },
    )

object HotFilter : TagFilter("sort", runBlocking { TRANSLATOR.translate("本週排行") }, true) // "Weekly Top"

class YearFilter(values: Array<String>) :
    QueryFilter(
        runBlocking { TRANSLATOR.translate("發佈年份") }, // "Release Year"
        "year",
        values.ifEmpty {
            arrayOf(runBlocking { TRANSLATOR.translate("全部年份") }), // "All Years"
        },
    )

class MonthFilter(values: Array<String>) :
    QueryFilter(
        runBlocking { TRANSLATOR.translate("發佈月份") }, // "Release Month"
        "month",
        values.ifEmpty {
            arrayOf(runBlocking { TRANSLATOR.translate("全部月份") }), // "All Months"
        },
    )

class DateFilter(yearFilter: YearFilter, monthFilter: MonthFilter) :
    AnimeFilter.Group<QueryFilter>(
        runBlocking { TRANSLATOR.translate("發佈日期") }, // "Release Date"
        listOf(yearFilter, monthFilter),
    )

class CategoryFilter(name: String, filters: List<TagFilter>) :
    AnimeFilter.Group<TagFilter>(name, filters)

class BroadMatchFilter : TagFilter("broad", runBlocking { TRANSLATOR.translate("廣泛配對") }) // "Broad Match"

class TagsFilter(filters: List<AnimeFilter<out Any>>) :
    AnimeFilter.Group<AnimeFilter<out Any>>(
        runBlocking { TRANSLATOR.translate("標籤") }, // "Tags"
        filters,
    )

// Google Cloud Translation API implementation
private object GoogleTranslator {
    private const val API_KEY = "AIzaSyCgTP2DCkLf8KzD3t3t7eZ_gR00rr3UIm0"
    private const val TRANSLATE_URL = "https://translation.googleapis.com/language/translate/v2"

    suspend fun translate(text: String): String {
        if (text.isBlank()) return text

        return try {
            val requestBody = """
                {
                    "q": "${text.replace("\"", "\\\"")}",
                    "source": "zh",
                    "target": "en",
                    "format": "text"
                }
            """.trimIndent()

            val response = networkService.newCall(
                Request.Builder()
                    .url("$TRANSLATE_URL?key=$API_KEY")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()
            ).await()

            if (!response.isSuccessful) return text

            val json = response.body.string()
            json.substringAfter("\"translatedText\":\"").substringBefore("\"")
        } catch (e: Exception) {
            text
        }
    }
}

// Translator instance (must be initialized in your main extension class)
private val TRANSLATOR by lazy { GoogleTranslator }
