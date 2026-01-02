package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

/**
 * Utility class for creating translated filters
 */
object FilterUtils {

    /**
     * Create a translated CategoryFilter
     */
    fun createTranslatedCategoryFilter(
        chineseCategory: String,
        chineseTags: List<String>,
    ): AnimeFilter.Group<AnimeFilter.CheckBox> {
        val translatedCategory = Tags.getTranslatedCategory(chineseCategory)
        val translatedTags = chineseTags.map { tag ->
            AnimeFilter.CheckBox(Tags.getTranslatedTag(tag))
        }
        return AnimeFilter.Group(translatedCategory, translatedTags)
    }

    /**
     * Create translated QueryFilter with options
     */
    fun createTranslatedQueryFilter(
        nameKey: String,
        filterKey: String,
        chineseOptions: Array<String>,
    ): AnimeFilter.Select<String> {
        val translatedName = when (nameKey) {
            "影片類型" -> "Genre"
            "排序方式" -> "Sort by"
            "發佈年份" -> "Release Year"
            "發佈月份" -> "Release Month"
            else -> nameKey
        }

        val translatedOptions = chineseOptions.map { option ->
            when (nameKey) {
                "影片類型" -> Tags.GENRE_TRANSLATIONS[option] ?: option
                "排序方式" -> Tags.SORT_TRANSLATIONS[option] ?: option
                "發佈年份" -> Tags.YEAR_TRANSLATIONS[option] ?: option
                "發佈月份" -> Tags.MONTH_TRANSLATIONS[option] ?: option
                else -> option
            }
        }.toTypedArray()

        return object : QueryFilter(translatedName, translatedOptions) {
            override val key: String = filterKey

            override val selected: String
                get() = if (state == 0) {
                    ""
                } else {
                    translatedOptions[state]
                }
        }
    }

    /**
     * Helper class to mimic the original QueryFilter behavior
     */
    abstract class QueryFilter(
        name: String,
        values: Array<String>,
    ) : AnimeFilter.Select<String>(name, values) {
        abstract val key: String
        abstract val selected: String
    }
}
