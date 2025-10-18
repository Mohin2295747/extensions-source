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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.atomic.AtomicReference

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
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val json: Json by injectLazy()

    private val filterUpdateState = AtomicReference(FilterUpdateState.NONE)
    private val uploadDateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
    }

    private val translator = Hanime1Translator()

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()

        val rawAnime =
            SAnime.create().apply {
                genre =
                    doc.select(".single-video-tag").not("[data-toggle]").eachText().joinToString()
                author = doc.select("#video-artist-name").text()

                // Parse JSON-LD block if available
                doc.select("script[type=application/ld+json]").firstOrNull()?.data()?.let { jsonData ->
                    try {
                        val info = json.decodeFromString<JsonElement>(jsonData).jsonObject
                        title = info["name"]?.jsonPrimitive?.content ?: ""
                        description = info["description"]?.jsonPrimitive?.content ?: ""
                        // Remove if SAnime has no thumbnail_url
                        thumbnail_url =
                            info["thumbnailUrl"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                    } catch (e: Exception) {
                        // Remove if Log not imported
                        // Log.e("Hanime1", "Failed to parse JSON-LD data", e)
                    }
                }

                // Fallback if JSON parsing failed
                if (title.isNullOrBlank()) {
                    title = doc.select("h1.video-title").text()
                }
                if (description.isNullOrBlank()) {
                    description = doc.select(".video-description").text()
                }

                // Optional translation hook (replace with real translator if used)
                if (!description.isNullOrEmpty()) {
                    val translatedDescription = translateText(getTargetLanguage(), description!!)
                    description = translatedDescription.ifEmpty { description!! }
                }

                // Optional extra metadata
                val type = doc.select("a#video-artist-name + a").text().trim()
                if (type == "裏番" || type == "泡麵番") {
                    runBlocking {
                        try {
                            val searchRequest =
                                searchAnimeRequest(1, title, AnimeFilterList(emptyList()))
                            val searchResponse = client.newCall(searchRequest).execute()
                            val searchPage = searchAnimeParse(searchResponse)
                            // Assign cover if available
                            thumbnail_url =
                                searchPage.animes.firstOrNull()?.thumbnail_url ?: thumbnail_url
                        } catch (e: Exception) {
                            // Log.e("Hanime1", "Failed to get bangumi cover image", e)
                        }
                    }
                }
            }

        return runBlocking { translator.translateAnimeDetails(rawAnime) }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val playlist = doc.select("#playlist-scroll").firstOrNull()
        val nodes = playlist?.select(">div") ?: emptyList()

        return nodes.mapIndexed { index, element ->
            SEpisode.create().apply {
                val href = element.select("a.overlay").attr("href")
                setUrlWithoutDomain(href)
                episode_number = (nodes.size - index).toFloat()
                name = element.select("div.card-mobile-title").text()

                if (href == response.request.url.toString()) {
                    doc.select("script[type=application/ld+json]").firstOrNull()?.data()?.let {
                            jsonData ->
                        try {
                            val info = json.decodeFromString<JsonElement>(jsonData).jsonObject
                            info["uploadDate"]?.jsonPrimitive?.content?.let { dateStr ->
                                date_upload =
                                    runCatching { uploadDateFormat.parse(dateStr)?.time }
                                        .getOrNull() ?: 0L
                            }
                        } catch (e: Exception) {
                            Log.e(name, "Failed to parse upload date", e)
                        }
                    }
                }
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val sourceList = doc.select("video source")
        val preferQuality = preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)

        val videos =
            sourceList.mapNotNull { source ->
                val quality = source.attr("size")
                val url = source.attr("src")
                if (url.isNotBlank() && !url.startsWith("blob:")) {
                    Video(url, "${quality}P", url)
                } else {
                    null
                }
            }

        if (videos.isNotEmpty()) {
            // This sorts but doesn’t guarantee exact quality match first if multiple qualities
            // exist;
            // you might want a different approach depending on preferences.
            return videos.sortedByDescending { it.quality == preferQuality }
        }

        // Fallback: try to find video from JSON-LD
        return doc.select("script[type=application/ld+json]").firstOrNull()?.data()?.let { jsonData ->
            try {
                val info = json.decodeFromString<JsonElement>(jsonData).jsonObject
                val videoUrl = info["contentUrl"]?.jsonPrimitive?.content
                if (!videoUrl.isNullOrBlank()) {
                    listOf(Video(videoUrl, "Raw", videoUrl))
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e("Hanime1", "Failed to parse video URL from JSON-LD", e)
                emptyList()
            }
        } ?: emptyList()
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        searchAnimeRequest(page, "", AnimeFilterList(emptyList()))

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int): Request =
        searchAnimeRequest(page, "", AnimeFilterList(listOf(HotFilter())))

    private fun String.appendInvisibleChar(): String = "$this\u200B"

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val nodes = doc.select("div.search-doujin-videos.hidden-xs:not(:has(a[target=_blank]))")

        val animeList =
            if (nodes.isNotEmpty()) {
                nodes.map { element ->
                    SAnime.create().apply {
                        setUrlWithoutDomain(element.select("a[class=overlay]").attr("href"))
                        thumbnail_url = element.select("img + img").attr("src")
                        title = element.select("div.card-mobile-title").text().appendInvisibleChar()
                        author = element.select(".card-mobile-user").text()
                    }
                }
            } else {
                doc.select("a:not([target]) > .search-videos").map { element ->
                    SAnime.create().apply {
                        setUrlWithoutDomain(element.parent()!!.attr("href"))
                        thumbnail_url = element.select("img").attr("src")
                        title =
                            element.select(".home-rows-videos-title").text().appendInvisibleChar()
                    }
                }
            }

        // Translate anime details
        val translatedAnimeList = runBlocking {
            animeList.map { anime ->
                try {
                    translator.translateAnimeDetails(anime)
                } catch (e: Exception) {
                    anime
                }
            }
        }

        val hasNextPage = doc.select("li.page-item a.page-link[rel=next]").isNotEmpty()
        return AnimesPage(translatedAnimeList, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")

        if (query.isNotEmpty()) {
            urlBuilder.addQueryParameter("query", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is QueryFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        urlBuilder.addQueryParameter(filter.key, filter.selected)
                    }
                }
                is TagFilter -> {
                    if (filter.state) {
                        urlBuilder.addQueryParameter(filter.key, filter.name)
                    }
                }
                is AnimeFilter.Group<*> -> {
                    filter.state.forEach { innerFilter ->
                        when (innerFilter) {
                            is QueryFilter -> {
                                if (innerFilter.selected.isNotEmpty()) {
                                    urlBuilder.addQueryParameter(
                                        innerFilter.key,
                                        innerFilter.selected,
                                    )
                                }
                            }
                            is TagFilter -> {
                                if (innerFilter.state) {
                                    urlBuilder.addQueryParameter(innerFilter.key, innerFilter.name)
                                }
                            }
                            else -> {
                                // No action for other inner types
                            }
                        }
                    }
                }
                else -> {
                    // No action for other outer types
                }
            }
        }

        return GET(urlBuilder.build().toString(), headers)
    }

    private fun checkFiltersInterceptor(chain: Interceptor.Chain): Response {
        if (filterUpdateState.get() == FilterUpdateState.NONE) {
            updateFilters()
        }
        return chain.proceed(chain.request())
    }

    private fun updateFilters() {
        if (!filterUpdateState.compareAndSet(FilterUpdateState.NONE, FilterUpdateState.UPDATING)) {
            return
        }

        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            filterUpdateState.set(FilterUpdateState.FAILED)
            Log.e("Hanime1", "Failed to update filters", throwable)
        }

        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            try {
                val response = client.newCall(GET("$baseUrl/search")).awaitSuccess()
                val doc = response.asJsoup()

                val genreList = doc.select("div.genre-option div.hentai-sort-options").eachText()
                val sortList =
                    doc.select("div.hentai-sort-options-wrapper div.hentai-sort-options").eachText()
                val yearList =
                    doc.select("select#year option").eachAttr("value").map {
                        if (it.isEmpty()) "全部年份" else it
                    }
                val monthList =
                    doc.select("select#month option").eachAttr("value").map {
                        if (it.isEmpty()) "全部月份" else it
                    }

                val categoryDict = mutableMapOf<String, MutableList<String>>()
                var currentCategory = ""

                // ✅ Corrected arrow placement here
                doc.select("div#tags div.modal-body").firstOrNull()?.children()?.forEach { element ->
                    when (element.tagName()) {
                        "h5" -> currentCategory = element.text()
                        "label" -> {
                            val value = element.select("input[name]").attr("value")
                            if (value.isNotEmpty()) {
                                categoryDict
                                    .getOrPut(currentCategory) { mutableListOf() }
                                    .add(value)
                            }
                        }
                    }
                }

                // Translate filter values if enabled
                val translatedGenreList =
                    if (translator.isTranslationEnabled()) {
                        translator.translateFilterValues(genreList)
                    } else {
                        genreList
                    }

                val translatedSortList =
                    if (translator.isTranslationEnabled()) {
                        translator.translateFilterValues(sortList)
                    } else {
                        sortList
                    }

                val translatedYearList =
                    if (translator.isTranslationEnabled()) {
                        translator.translateFilterValues(yearList)
                    } else {
                        yearList
                    }

                val translatedMonthList =
                    if (translator.isTranslationEnabled()) {
                        translator.translateFilterValues(monthList)
                    } else {
                        monthList
                    }

                val translatedCategoryDict =
                    if (translator.isTranslationEnabled()) {
                        runBlocking {
                            categoryDict.mapValues { (_, values) ->
                                translator.translateFilterValues(values)
                            }
                        }
                    } else {
                        categoryDict
                    }

                preferences
                    .edit()
                    .putString(PREF_KEY_GENRE_LIST, translatedGenreList.joinToString(","))
                    .putString(PREF_KEY_SORT_LIST, translatedSortList.joinToString(","))
                    .putString(PREF_KEY_YEAR_LIST, translatedYearList.joinToString(","))
                    .putString(PREF_KEY_MONTH_LIST, translatedMonthList.joinToString(","))
                    .putString(PREF_KEY_CATEGORY_LIST, json.encodeToString(translatedCategoryDict))
                    .apply()

                filterUpdateState.set(FilterUpdateState.COMPLETED)
            } catch (e: Exception) {
                filterUpdateState.set(FilterUpdateState.FAILED)
                throw e
            }
        }
    }

    private fun <T : QueryFilter> createFilter(prefKey: String, block: (Array<String>) -> T): T {
        val savedOptions = preferences.getString(prefKey, null)
        val options =
            if (savedOptions.isNullOrEmpty()) {
                emptyArray()
            } else {
                savedOptions.split(",").map { it.trim() }.toTypedArray()
            }

        return if (!translator.isTranslationEnabled() || options.isEmpty()) {
            block(options)
        } else {
            runBlocking {
                try {
                    val translatedOptions = translator.translateFilterValues(options.toList())
                    block(translatedOptions.toTypedArray())
                } catch (e: Exception) {
                    block(options)
                }
            }
        }
    }

    private fun createCategoryFilters(): List<AnimeFilter<*>> {
        val result = mutableListOf<AnimeFilter<*>>(BroadMatchFilter())

        val savedCategories = preferences.getString(PREF_KEY_CATEGORY_LIST, null)
        if (savedCategories.isNullOrEmpty()) {
            return result
        }

        return runBlocking {
            try {
                val categoryDict =
                    json.decodeFromString<Map<String, List<String>>>(savedCategories)
                categoryDict.map { (key, values) ->
                    val translatedKey =
                        if (translator.isTranslationEnabled()) {
                            translator.fastTranslateFilterText(key)
                        } else {
                            key
                        }

                    val tagFilters =
                        values.map { value ->
                            val translatedValue =
                                if (translator.isTranslationEnabled()) {
                                    translator.fastTranslateFilterText(value)
                                } else {
                                    value
                                }
                            TagFilter("tags[]", translatedValue)
                        }

                    CategoryFilter(translatedKey, tagFilters)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
            .also { result.addAll(it) }
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
        screen.addTranslationPreferences()

        screen.addPreference(
            ListPreference(screen.context).apply {
                key = PREF_KEY_VIDEO_QUALITY
                title = "Preferred Video Quality"
                entries = arrayOf("1080P", "720P", "480P")
                entryValues = entries
                setDefaultValue(DEFAULT_QUALITY)
                summary =
                    "Current: ${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"
                setOnPreferenceChangeListener { _, newValue ->
                    summary = "Current: ${newValue as String}"
                    true
                }
            },
        )

        screen.addPreference(
            ListPreference(screen.context).apply {
                key = PREF_KEY_LANG
                title = "Preferred Language"
                summary = "This setting only affects video subtitles"
                entries = arrayOf("繁體中文", "簡體中文")
                entryValues = arrayOf("zh-CHT", "zh-CHS")
                setOnPreferenceChangeListener { _, newValue ->
                    val cookie =
                        Cookie.parse(baseUrl.toHttpUrl(), "user_lang=${newValue as String}")
                    if (cookie != null) {
                        client.cookieJar.saveFromResponse(baseUrl.toHttpUrl(), listOf(cookie))
                    }
                    true
                }
            },
        )
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
