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
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

private const val TAG = "Hanime1"

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

    override val client = network.client.newBuilder().addInterceptor(::checkFiltersInterceptor).build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val json by injectLazy<Json>()
    private var filterUpdateState = FilterUpdateState.NONE
    private val uploadDateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
    }

    // translator instance
    private val translator = Hanime1Translator()

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val rawAnime = SAnime.create().apply {
            genre = doc.select(".single-video-tag").not("[data-toggle]").eachText().joinToString()
            author = doc.select("#video-artist-name").text().ifBlank { null }
            // Parse structured JSON-LD safely
            doc.select("script[type=application/ld+json]").firstOrNull()?.data()?.let { data ->
                try {
                    val info = json.decodeFromString<JsonElement>(data).jsonObject
                    title = info["name"]?.jsonPrimitive?.contentOrNull().orEmpty()
                    description = info["description"]?.jsonPrimitive?.contentOrNull().orEmpty()
                    thumbnail_url = info["thumbnailUrl"]?.jsonArray?.getOrNull(0)?.jsonPrimitive?.content
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse JSON-LD for anime details: ${e.message}")
                }
            }

            val type = doc.select("a#video-artist-name + a").text().trim()
            if (type == "裏番" || type == "泡麵番") {
                // try to get series cover if available
                try {
                    val animesPage = getSearchAnime(
                        1,
                        title,
                        AnimeFilterList(GenreFilter(arrayOf("", type)).apply { state = 1 }),
                    )
                    if (animesPage.animes.isNotEmpty()) {
                        thumbnail_url = animesPage.animes.first().thumbnail_url
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get bangumi cover image: ${e.message}")
                }
            }
        }

        // attempt translation (blocking) but fall back to original on error
        return try {
            translator.translateAnimeDetails(rawAnime)
        } catch (e: Exception) {
            Log.w(TAG, "Translation failed: ${e.message}")
            rawAnime
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        val playlist = jsoup.select("#playlist-scroll").firstOrNull()
        val nodes = playlist?.select(">div") ?: emptyList()
        return nodes.mapIndexed { index, element ->
            SEpisode.create().apply {
                val href = element.select("a.overlay").attr("href")
                setUrlWithoutDomain(href)
                episode_number = (nodes.size - index).toFloat()
                name = element.select("div.card-mobile-title").text().ifBlank { "" }

                if (href == response.request.url.toString()) {
                    // current video: try to parse uploadDate from JSON-LD if present
                    jsoup.select("script[type=application/ld+json]").firstOrNull()?.data()?.let { data ->
                        try {
                            val info = json.decodeFromString<JsonElement>(data).jsonObject
                            info["uploadDate"]?.jsonPrimitive?.contentOrNull()?.let { date ->
                                date_upload = kotlin.runCatching { uploadDateFormat.parse(date)?.time }.getOrNull() ?: 0L
                            }
                        } catch (e: Exception) {
                            // ignore parsing errors
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

        val sources = sourceList.mapNotNull {
            val quality = it.attr("size")
            val url = it.attr("src").ifBlank { null }
            url?.let { u -> Video(u, "${quality}P", videoUrl = u) }
        }.filterNot { it.videoUrl?.startsWith("blob") == true }

        val sorted = sources.sortedByDescending { it.quality == preferQuality }
        return if (sorted.isNotEmpty()) sorted else {
            // Fallback: try to read from JSON-LD contentUrl
            val videoUrl = doc.select("script[type=application/ld+json]").firstOrNull()?.data()?.let { data ->
                try {
                    val info = json.decodeFromString<JsonElement>(data).jsonObject
                    info["contentUrl"]?.jsonPrimitive?.content
                } catch (e: Exception) {
                    null
                }
            }
            if (videoUrl != null) listOf(Video(videoUrl, "Raw", videoUrl = videoUrl)) else emptyList()
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int) =
        searchAnimeRequest(page, "", AnimeFilterList(HotFilter))

    private fun String.appendInvisibleChar(): String = "$this\u200B"

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("div.search-doujin-videos.hidden-xs:not(:has(a[target=_blank]))")
        val list = if (nodes.isNotEmpty()) {
            nodes.map { element ->
                val anime = SAnime.create().apply {
                    setUrlWithoutDomain(element.select("a[class=overlay]").attr("href"))
                    thumbnail_url = element.select("img + img").attr("src").ifBlank { null }
                    title = element.select("div.card-mobile-title").text().appendInvisibleChar()
                    author = element.select(".card-mobile-user").text().ifBlank { null }
                }
                // translate (blocking) and fall back to original on error
                try {
                    translator.translateAnimeDetails(anime)
                } catch (e: Exception) {
                    anime
                }
            }
        } else {
            jsoup.select("a:not([target]) > .search-videos").map { element ->
                val anime = SAnime.create().apply {
                    setUrlWithoutDomain(element.parent()?.attr("href").orEmpty())
                    thumbnail_url = element.select("img").attr("src").ifBlank { null }
                    title = element.select(".home-rows-videos-title").text().appendInvisibleChar()
                }
                try {
                    translator.translateAnimeDetails(anime)
                } catch (e: Exception) {
                    anime
                }
            }
        }
        val nextPage = jsoup.select("li.page-item a.page-link[rel=next]").isNotEmpty()
        return AnimesPage(list, nextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchUrlBuilder = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
        if (query.isNotEmpty()) searchUrlBuilder.addQueryParameter("query", query)

        // flatten filters into a simple list to process
        val items = filters.list.flatMap { f ->
            when (f) {
                is TagsFilter -> f.state.flatMap { inner ->
                    if (inner is CategoryFilter) inner.state else listOf(inner)
                }
                is AnimeFilter.Group<*> -> f.state
                else -> listOf(f)
            }
        }

        for (it in items) {
            when (it) {
                is QueryFilter -> {
                    val sel = it.selected
                    if (sel.isNotEmpty()) searchUrlBuilder.addQueryParameter(it.key, sel)
                }
                is BroadMatchFilter -> {
                    if (it.state) searchUrlBuilder.addQueryParameter(it.key, "on")
                }
                is TagFilter -> {
                    if (it.state) searchUrlBuilder.addQueryParameter(it.key, it.name)
                }
                else -> {
                    // ignore unknown types
                }
            }
        }

        if (page > 1) searchUrlBuilder.addQueryParameter("page", "$page")
        return GET(searchUrlBuilder.build())
    }

    private fun checkFiltersInterceptor(chain: Interceptor.Chain): Response {
        if (filterUpdateState == FilterUpdateState.NONE) {
            updateFilters()
        }
        return chain.proceed(chain.request())
    }

    private fun updateFilters() {
        filterUpdateState = FilterUpdateState.UPDATING

        val handler = CoroutineExceptionHandler { _, ex ->
            Log.w(TAG, "Filter update failed: ${ex.message}")
            filterUpdateState = FilterUpdateState.FAILED
        }

        CoroutineScope(Dispatchers.IO).launch(handler) {
            try {
                val searchResponse = client.newCall(GET("$baseUrl/search")).execute()
                val jsoup = searchResponse.use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("Failed to fetch search page")
                    resp.body?.string().orEmpty().let { body -> body.asJsoup() }
                }

                val genreList = jsoup.select("div.genre-option div.hentai-sort-options").eachText()
                val sortList = jsoup.select("div.hentai-sort-options-wrapper div.hentai-sort-options").eachText()
                val yearList = jsoup.select("select#year option").eachAttr("value").map { it.ifEmpty { "全部年份" } }
                val monthList = jsoup.select("select#month option").eachAttr("value").map { it.ifEmpty { "全部月份" } }

                val categoryDict = mutableMapOf<String, MutableList<String>>()
                var currentKey = ""
                jsoup.select("div#tags div.modal-body").firstOrNull()?.children()?.forEach { child ->
                    when (child.tagName()) {
                        "h5" -> currentKey = child.text()
                        "label" -> {
                            val value = child.select("input[name]").attr("value")
                            categoryDict.getOrPut(currentKey) { mutableListOf() }.add(value)
                        }
                    }
                }

                // Translate if enabled
                val translatedGenreList = if (translator.isTranslationEnabled()) translator.translateFilterValues(genreList) else genreList
                val translatedSortList = if (translator.isTranslationEnabled()) translator.translateFilterValues(sortList) else sortList
                val translatedYearList = if (translator.isTranslationEnabled()) translator.translateFilterValues(yearList) else yearList
                val translatedMonthList = if (translator.isTranslationEnabled()) translator.translateFilterValues(monthList) else monthList

                val translatedCategoryDict = if (translator.isTranslationEnabled()) {
                    categoryDict.mapValues { (_, values) -> translator.translateFilterValues(values) }
                } else {
                    categoryDict
                }

                preferences.edit()
                    .putString(PREF_KEY_GENRE_LIST, translatedGenreList.joinToString(","))
                    .putString(PREF_KEY_SORT_LIST, translatedSortList.joinToString(","))
                    .putString(PREF_KEY_YEAR_LIST, translatedYearList.joinToString(","))
                    .putString(PREF_KEY_MONTH_LIST, translatedMonthList.joinToString(","))
                    .putString(PREF_KEY_CATEGORY_LIST, json.encodeToString(translatedCategoryDict))
                    .apply()

                filterUpdateState = FilterUpdateState.COMPLETED
            } catch (e: Exception) {
                Log.w(TAG, "Exception updating filters: ${e.message}")
                filterUpdateState = FilterUpdateState.FAILED
            }
        }
    }

    private fun <T : QueryFilter> createFilter(prefKey: String, block: (Array<String>) -> T): T {
        val savedOptions = preferences.getString(prefKey, "") ?: ""
        if (savedOptions.isBlank()) return block(emptyArray())

        val options = savedOptions.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

        if (!translator.isTranslationEnabled()) return block(options)

        return try {
            val translated = runCatching { kotlinx.coroutines.runBlocking { translator.translateFilterValues(options.toList()) } }
                .getOrNull() ?: options.toList()
            block(translated.toTypedArray())
        } catch (e: Exception) {
            block(options)
        }
    }

    private fun createCategoryFilters(): List<AnimeFilter<out Any>> {
        val result = mutableListOf<AnimeFilter<out Any>>(BroadMatchFilter())
        val savedCategories = preferences.getString(PREF_KEY_CATEGORY_LIST, "") ?: ""
        if (savedCategories.isBlank()) return result

        val parsed: Map<String, List<String>> = try {
            json.decodeFromString(savedCategories)
        } catch (e: Exception) {
            emptyMap()
        }

        parsed.forEach { (key, values) ->
            val translatedKey = if (translator.isTranslationEnabled()) {
                runCatching { kotlinx.coroutines.runBlocking { translator.fastTranslateFilterText(key) } }.getOrNull() ?: key
            } else {
                key
            }

            val translatedFilters = values.map { value ->
                val label = if (translator.isTranslationEnabled()) {
                    runCatching { kotlinx.coroutines.runBlocking { translator.fastTranslateFilterText(value) } }.getOrNull() ?: value
                } else {
                    value
                }
                TagFilter("tags[]", label)
            }

            result.add(CategoryFilter(translatedKey, translatedFilters))
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
            // translation options
            addTranslationPreferences()

            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_VIDEO_QUALITY
                    title = "Preferred Video Quality"
                    entries = arrayOf("1080P", "720P", "480P")
                    entryValues = entries
                    setDefaultValue(DEFAULT_QUALITY)
                    summary = "Current: ${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"
                    setOnPreferenceChangeListener { _, newValue ->
                        summary = "Current: ${newValue as String}"
                        true
                    }
                },
            )

            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_LANG
                    title = "Preferred Language"
                    summary = "This setting only affects video subtitles"
                    entries = arrayOf("繁體中文", "簡體中文")
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
