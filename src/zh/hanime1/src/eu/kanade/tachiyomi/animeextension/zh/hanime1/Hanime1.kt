package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

enum class FilterUpdateState {
    NONE,
    UPDATING,
    COMPLETED,
    FAILED,
}

class Hanime1 : AnimeHttpSource(), ConfigurableAnimeSource {
    override val baseUrl: String
        get() = "https://hanime1.me"
    override val lang: String
        get() = "zh"
    override val name: String
        get() = "Hanime1.me"
    override val supportsLatest: Boolean
        get() = true

    override val client =
        network.client.newBuilder().addInterceptor(::checkFiltersInterceptor).build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val json by injectLazy<Json>()
    private var filterUpdateState = FilterUpdateState.NONE
    private val uploadDateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            genre = doc.select(".single-video-tag").not("[data-toggle]").eachText().joinToString()
            author = doc.select("#video-artist-name").text()
            val realTitle = doc.select("div.video-description-panel > div:nth-child(2)").text()
            title = realTitle.appendInvisibleChar()
            description = doc.select("div.video-description-panel > div:nth-child(3)").text()
            thumbnail_url = doc.select("video[poster]").attr("poster")
            val type = doc.select("a#video-artist-name + a").text().trim()
            if (type == "裏番" || type == "泡麵番") {
                runBlocking {
                    try {
                        val animesPage =
                            getSearchAnime(
                                1,
                                realTitle,
                                AnimeFilterList(GenreFilter(arrayOf("", type)).apply { state = 1 }),
                            )
                        thumbnail_url = animesPage.animes.first().thumbnail_url
                    } catch (e: Exception) {
                        Log.e(name, "Failed to get bangumi cover image", e)
                    }
                }
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("#playlist-scroll").first()!!.select(">div")
        return nodes.mapIndexed { index, element ->
            SEpisode.create().apply {
                val href = element.select("a.overlay").attr("href")
                setUrlWithoutDomain(href)
                episode_number = (nodes.size - index).toFloat()
                name = element.select("div.card-mobile-title").text()
                if (href == response.request.url.toString()) {
                    val timeStr =
                        jsoup.select("div.video-description-panel > div:first-child").text()
                            .split(" ").last()
                    date_upload =
                        runCatching { uploadDateFormat.parse(timeStr)?.time }.getOrNull() ?: 0L
                }
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val sourceList = doc.select("video source")
        val preferredQuality = preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)
        return sourceList.map {
            val quality = it.attr("size")
            val url = it.attr("src")
            Video(url, "${quality}P", videoUrl = url)
        }.sortedByDescending { preferredQuality == it.quality }
            .ifEmpty {
                val videoUrl = doc.select("script:containsData(source)").first()!!.data()
                    .substringAfter("source = '").substringBefore("'")
                listOf(Video(videoUrl, "Raw", videoUrl = videoUrl))
            }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("sort", "他們在看")
        if (page > 1) {
            url.addQueryParameter("page", "$page")
        }
        return GET(url.build())
    }

    private fun String.appendInvisibleChar(): String {
        return "${this}\u200B"
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select(".horizontal-row .video-item-container:not(:has(a.video-link[target]))")
        val list = if (nodes.isNotEmpty()) {
            nodes.map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.select("a.video-link").attr("href"))
                    thumbnail_url = it.select(".main-thumb").attr("abs:src")
                    title = it.select(".title").text().appendInvisibleChar()
                    author = it.select(".subtitle").text().split("•").getOrNull(0)?.trim()
                }
            }
        } else {
            jsoup.select("a:not([target]) > .search-videos").map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.parent()!!.attr("href"))
                    thumbnail_url = it.select("img").attr("src")
                    title = it.select(".home-rows-videos-title").text().appendInvisibleChar()
                }
            }
        }
        val nextPage = jsoup.select("li.page-item a.page-link[rel=next]")
        return AnimesPage(list, nextPage.isNotEmpty())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchUrl = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
        if (query.isNotEmpty()) {
            searchUrl.addQueryParameter("query", query)
        }

        val queryFilters = mutableListOf<QueryFilter>()

        filters.list.flatMap {
            when (it) {
                is TagsFilter -> {
                    it.state.flatMap { inner ->
                        if (inner is CategoryFilter) {
                            inner.state
                        } else {
                            listOf(inner)
                        }
                    }
                }
                is AnimeFilter.Group<*> -> it.state
                else -> listOf(it)
            }
        }.forEach { filter ->
            when (filter) {
                is QueryFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        searchUrl.addQueryParameter(filter.key, filter.selected)
                        queryFilters.add(filter)
                    }
                }
                is BroadMatchFilter -> {
                    if (filter.state) {
                        searchUrl.addQueryParameter("broad", "on")
                    }
                }
                is TagFilter -> {
                    if (filter.state) {
                        searchUrl.addQueryParameter("tags[]", filter.originalValue)
                    }
                }
                else -> {}
            }
        }

        val yearFilter = queryFilters.find { it.key == "year" }
        val monthFilter = queryFilters.find { it.key == "month" }

        if (yearFilter != null || monthFilter != null) {
            val dateParam = buildString {
                yearFilter?.selected?.let {
                    if (it != "all-years" && it.isNotEmpty()) {
                        append(it)
                    }
                }
                monthFilter?.selected?.let {
                    if (it != "all-months" && it.isNotEmpty()) {
                        append(it)
                    }
                }
            }
            if (dateParam.isNotEmpty()) {
                searchUrl.addQueryParameter("date", dateParam)
            }
        }

        searchUrl.addQueryParameter("type", "")
        searchUrl.addQueryParameter("duration", "")

        if (page > 1) {
            searchUrl.addQueryParameter("page", "$page")
        }

        return GET(searchUrl.build())
    }

    private fun checkFiltersInterceptor(chain: Interceptor.Chain): Response {
        if (filterUpdateState == FilterUpdateState.NONE) {
            updateFilters()
        }
        return chain.proceed(chain.request())
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun updateFilters() {
        filterUpdateState = FilterUpdateState.UPDATING
        val exceptionHandler =
            CoroutineExceptionHandler { _, _ -> filterUpdateState = FilterUpdateState.FAILED }
        GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
            try {
                val jsoup = client.newCall(GET("$baseUrl/search")).awaitSuccess().asJsoup()

                val genreList = jsoup.select("div.genre-option div.hentai-sort-options").eachText()
                val sortList = jsoup.select("div.hentai-sort-options-wrapper div.hentai-sort-options").eachText()
                val yearList = jsoup.select("select#date-year option").eachAttr("value")
                    .map { it.ifEmpty { "all-years" } }
                val monthList = jsoup.select("select#date-month option").eachAttr("value")
                    .map { it.ifEmpty { "all-months" } }

                val categoryDict = mutableMapOf<String, MutableList<String>>()

                jsoup.select("div#tags div.modal-body").first()?.children()?.forEach { element ->
                    when {
                        element.tagName() == "h5" -> {
                            val chineseCategory = element.text()
                            val categoryKey = when (chineseCategory) {
                                "影片屬性" -> "video_attributes"
                                "人物關係" -> "character_relationships"
                                "角色設定" -> "characteristics"
                                "外貌身材" -> "appearance_and_figure"
                                "情境場所" -> "story_location"
                                "故事劇情" -> "story_plot"
                                "性交體位" -> "sex_positions"
                                else -> chineseCategory
                            }
                            categoryDict.getOrPut(categoryKey) { mutableListOf() }
                        }
                        element.tagName() == "label" -> {
                            val tagValue = element.select("input[name]").attr("value")
                            if (tagValue.isNotEmpty()) {
                                if (categoryDict.isNotEmpty()) {
                                    val lastKey = categoryDict.keys.last()
                                    categoryDict[lastKey]?.add(tagValue)
                                }
                            }
                        }
                    }
                }

                preferences.edit()
                    .putString(PREF_KEY_GENRE_LIST, genreList.joinToString("|"))
                    .putString(PREF_KEY_SORT_LIST, sortList.joinToString("|"))
                    .putString(PREF_KEY_YEAR_LIST, yearList.joinToString("|"))
                    .putString(PREF_KEY_MONTH_LIST, monthList.joinToString("|"))
                    .putString(PREF_KEY_CATEGORY_LIST, json.encodeToString(categoryDict))
                    .apply()

                filterUpdateState = FilterUpdateState.COMPLETED
            } catch (e: Exception) {
                Log.e(name, "Failed to update filters", e)
                filterUpdateState = FilterUpdateState.FAILED
            }
        }
    }

    private fun <T : QueryFilter> createFilter(
        prefKey: String,
        block: (Array<String>) -> T,
    ): T {
        val savedOptions = preferences.getString(prefKey, "")
        return if (savedOptions.isNullOrEmpty()) {
            block(emptyArray())
        } else {
            block(savedOptions.split("|").toTypedArray())
        }
    }

    private fun createCategoryFilters(): List<AnimeFilter<out Any>> {
        val result = mutableListOf<AnimeFilter<out Any>>(
            BroadMatchFilter(),
        )

        val savedCategories = preferences.getString(PREF_KEY_CATEGORY_LIST, "")
        if (savedCategories.isNullOrEmpty()) {
            return result
        }

        try {
            json.decodeFromString<Map<String, List<String>>>(savedCategories).forEach { (category, tags) ->
                val translatedCategory = Tags.getTranslatedCategory(category)
                val tagFilters = tags.map { tag ->
                    val translatedTag = Tags.getTranslatedTag(tag)
                    TagFilter("tags[]", translatedTag, tag)
                }
                if (tagFilters.isNotEmpty()) {
                    result.add(
                        CategoryFilter(
                            translatedCategory,
                            tagFilters,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Failed to decode category filters", e)
        }

        return result
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            createFilter(PREF_KEY_GENRE_LIST) { GenreFilter(it) },
            createFilter(PREF_KEY_SORT_LIST) { SortFilter(it) },
            DateFilter(
                createFilter(PREF_KEY_YEAR_LIST) { YearFilter(it) },
                createFilter(PREF_KEY_MONTH_LIST) { MonthFilter(it) },
            ),
            TagsFilter(createCategoryFilters()),
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.apply {
            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_VIDEO_QUALITY
                    title = "Set preferred quality"
                    entries = arrayOf("1080P", "720P", "480P")
                    entryValues = entries
                    setDefaultValue(DEFAULT_QUALITY)
                    summary = "Current selection: ${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"
                    setOnPreferenceChangeListener { _, newValue ->
                        summary = "Current selection: ${newValue as String}"
                        true
                    }
                },
            )
            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_LANG
                    title = "Set preferred language"
                    summary = "This setting only affects video subtitles"
                    entries = arrayOf("Traditional Chinese", "Simplified Chinese")
                    entryValues = arrayOf("zh-CHT", "zh-CHS")
                    setOnPreferenceChangeListener { _, newValue ->
                        val baseHttpUrl = baseUrl.toHttpUrl()
                        client.cookieJar.saveFromResponse(
                            baseHttpUrl,
                            listOf(
                                Cookie.parse(
                                    baseHttpUrl,
                                    "user_lang=${newValue as String}",
                                )!!,
                            ),
                        )
                        true
                    }
                },
            )
        }
    }

    companion object {
        const val PREF_KEY_VIDEO_QUALITY = "PREF_KEY_VIDEO_QUALITY"
        const val PREF_KEY_LANG = "PREF_KEY_LANG"
        const val PREF_KEY_GENRE_LIST = "PREF_KEY_GENRE_LIST"
        const val PREF_KEY_SORT_LIST = "PREF_KEY_SORT_LIST"
        const val PREF_KEY_YEAR_LIST = "PREF_KEY_YEAR_LIST"
        const val PREF_KEY_MONTH_LIST = "PREF_KEY_MONTH_LIST"
        const val PREF_KEY_CATEGORY_LIST = "PREF_KEY_CATEGORY_LIST"
        const val DEFAULT_QUALITY = "1080P"
    }
}
