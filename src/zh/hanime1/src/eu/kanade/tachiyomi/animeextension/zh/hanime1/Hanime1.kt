package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
    }

    // Duration regex to match time formats like 16:17 or 1:02:33
    private val durationRegex = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$")

    // Helper function to extract duration from a card element
    private fun extractDurationFromCard(card: org.jsoup.nodes.Element): String? {
        // Look through descendant elements for time-looking text
        return card.select("div, span")
            .firstOrNull { el -> 
                durationRegex.matches(el.text().trim())
            }?.text()?.trim()
    }

    // Helper function to create SAnime from a card with duration
    private fun animeFromCard(card: org.jsoup.nodes.Element): SAnime {
        // Try different selectors for title extraction
        val titleText = when {
            // Search results page
            card.select(".home-rows-videos-title").isNotEmpty() -> 
                card.select(".home-rows-videos-title").text().trim()
            
            // Mobile title
            card.select(".card-mobile-title").isNotEmpty() -> 
                card.select(".card-mobile-title").text().trim()
            
            // Fallback: longest div text
            else -> card.select("div").eachText()
                .maxByOrNull { it.length }?.trim() ?: ""
        }

        // Extract duration from the same card
        val durationText = extractDurationFromCard(card)

        // Append duration to title if found
        val finalTitle = if (!durationText.isNullOrBlank()) {
            "$titleText [$durationText]"
        } else {
            titleText
        }

        return SAnime.create().apply {
            title = finalTitle
            setUrlWithoutDomain(card.attr("href"))
            
            // Try different selectors for thumbnail
            when {
                card.select("img + img").isNotEmpty() -> 
                    thumbnail_url = card.select("img + img").attr("src")
                card.select("img").isNotEmpty() -> 
                    thumbnail_url = card.select("img").attr("src")
                else -> thumbnail_url = ""
            }
            
            // Extract author if available
            if (card.select(".card-mobile-user").isNotEmpty()) {
                author = card.select(".card-mobile-user").text()
            }
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val useEnglish = preferences.getBoolean(PREF_KEY_USE_ENGLISH, true)
        
        return SAnime.create().apply {
            // Get tags and translate them if English is enabled
            val tags = doc.select(".single-video-tag").not("[data-toggle]").eachText()
            genre = if (useEnglish) {
                // Translate tags to English
                tags.map { chineseTag ->
                    Tags.getTranslatedTag(chineseTag) ?: chineseTag
                }.joinToString()
            } else {
                tags.joinToString()
            }
            
            author = doc.select("#video-artist-name").text()
            doc.select("script[type=application/ld+json]").first()?.data()?.let {
                val info = json.decodeFromString<JsonElement>(it).jsonObject
                title = info["name"]!!.jsonPrimitive.content
                description = info["description"]!!.jsonPrimitive.content
                thumbnail_url = info["thumbnailUrl"]?.jsonArray?.get(0)?.jsonPrimitive?.content
            }
            val type = doc.select("a#video-artist-name + a").text().trim()
            if (type == "裏番" || type == "泡麵番") {
                // Use the series cover image for bangumi entries instead of the episode image.
                runBlocking {
                    try {
                        val animesPage =
                            getSearchAnime(
                                1,
                                title,
                                AnimeFilterList(GenreFilter(arrayOf("", type)).apply { state = 1 }),
                            )
                        thumbnail_url = animesPage.animes.first().thumbnail_url
                    } catch (e: Exception) {
                        Log.e(name, "Failed to get bangumi cover image")
                    }
                }
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("#playlist-scroll").first()!!.select(">div")
        val currentUrl = response.request.url.toString()
        
        return nodes.mapIndexed { index, element ->
            SEpisode.create().apply {
                val href = element.select("a.overlay").attr("href")
                setUrlWithoutDomain(href)
                episode_number = (nodes.size - index).toFloat()
                name = element.select("div.card-mobile-title").text()
                
                if (href == currentUrl) {
                    // This is the currently opened video - set date to very old so it appears as episode 1
                    date_upload = 1L // Set to Jan 1, 1970 (very old)
                } else if (href == response.request.url.toString()) {
                    // Current video from the request URL (fallback)
                    jsoup.select("script[type=application/ld+json]").first()?.data()?.let {
                        val info = json.decodeFromString<JsonElement>(it).jsonObject
                        info["uploadDate"]?.jsonPrimitive?.content?.let { date ->
                            date_upload = runCatching { uploadDateFormat.parse(date)?.time }.getOrNull() ?: 0L
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
        return sourceList.map {
            val quality = it.attr("size")
            val url = it.attr("src")
            Video(url, "${quality}P", videoUrl = url)
        }.filterNot { it.videoUrl?.startsWith("blob") == true }
            .sortedByDescending { preferQuality == it.quality }
            .ifEmpty {
                // Try to find the source from the script content.
                val videoUrl = doc.select("script[type=application/ld+json]").first()!!.data().let {
                    val info = json.decodeFromString<JsonElement>(it).jsonObject
                    info["contentUrl"]!!.jsonPrimitive.content
                }
                listOf(Video(videoUrl, "Raw", videoUrl = videoUrl))
            }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int) =
        searchAnimeRequest(page, "", AnimeFilterList(HotFilter()))

    private fun String.appendInvisibleChar(): String {
        // The search result title will be same as one episode name of anime.
        // Adding extra char makes them has different title
        return "${this}\u200B"
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        
        // Handle different page layouts
        val cards = when {
            // Search results with doujin videos
            jsoup.select("div.search-doujin-videos.hidden-xs:not(:has(a[target=_blank]))").isNotEmpty() -> 
                jsoup.select("div.search-doujin-videos.hidden-xs:not(:has(a[target=_blank]))")
            
            // Regular search results
            jsoup.select("a:not([target]) > .search-videos").isNotEmpty() -> 
                jsoup.select("a:not([target]) > .search-videos").map { it.parent()!! }
            
            // Latest updates and popular anime pages
            else -> jsoup.select("a[href^=/watch]:has(.home-rows-videos-title)")
        }
        
        val list = cards.map { card ->
            val anime = animeFromCard(card)
            // Add invisible character to titles in search results to avoid duplicates
            if (jsoup.select("div.search-doujin-videos").isNotEmpty() || 
                jsoup.select("a:not([target]) > .search-videos").isNotEmpty()) {
                anime.title = anime.title.appendInvisibleChar()
            }
            anime
        }
        
        val nextPage = jsoup.select("li.page-item a.page-link[rel=next]")
        return AnimesPage(list, nextPage.isNotEmpty())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchUrl = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
        if (query.isNotEmpty()) {
            searchUrl.addQueryParameter("query", query)
        }
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
        }.forEach {
            when (it) {
                is QueryFilter -> {
                    if (it.selected.isNotEmpty()) {
                        // For QueryFilters, we need to send the Chinese value
                        val chineseValue = when (it.key) {
                            "genre" -> Tags.getOriginalGenre(it.selected) ?: it.selected
                            "sort" -> Tags.getOriginalSort(it.selected) ?: it.selected
                            "year" -> Tags.getOriginalYear(it.selected) ?: it.selected
                            "month" -> Tags.getOriginalMonth(it.selected) ?: it.selected
                            else -> it.selected
                        }
                        searchUrl.addQueryParameter(it.key, chineseValue)
                    }
                }

                is BroadMatchFilter -> {
                    if (it.state) {
                        searchUrl.addQueryParameter(it.key, "on")
                    }
                }

                is TagFilter -> {
                    if (it.state) {
                        // Send the original Chinese tag value to the server
                        val chineseTag = Tags.getOriginalTag(it.name) ?: it.name
                        searchUrl.addQueryParameter(it.key, chineseTag)
                    }
                }

                else -> {}
            }
        }
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
            val jsoup = client.newCall(GET("$baseUrl/search")).awaitSuccess().asJsoup()
            // Extract Chinese lists
            val chineseGenreList = jsoup.select("div.genre-option div.hentai-sort-options").eachText()
            val chineseSortList =
                jsoup.select("div.hentai-sort-options-wrapper div.hentai-sort-options").eachText()
            val chineseYearList = jsoup.select("select#year option").eachAttr("value")
                .map { it.ifEmpty { "全部年份" } }
            val chineseMonthList = jsoup.select("select#month option").eachAttr("value")
                .map { it.ifEmpty { "全部月份" } }
            val categoryDict = mutableMapOf<String, MutableList<String>>()
            var currentKey = ""
            jsoup.select("div#tags div.modal-body").first()?.children()?.forEach {
                if (it.tagName() == "h5") {
                    currentKey = it.text()
                    categoryDict[currentKey] = mutableListOf()
                }
                if (it.tagName() == "label") {
                    val inputTag = it.select("input[name]")
                    if (inputTag.isNotEmpty()) {
                        val tagValue = inputTag.attr("value").trim()
                        if (tagValue.isNotEmpty() && currentKey.isNotEmpty()) {
                            categoryDict[currentKey]?.add(tagValue)
                        }
                    }
                }
            }
            // Save Chinese versions (server needs these)
            preferences.edit()
                .putString(PREF_KEY_CHINESE_GENRE_LIST, chineseGenreList.joinToString(SEPARATOR))
                .putString(PREF_KEY_CHINESE_SORT_LIST, chineseSortList.joinToString(SEPARATOR))
                .putString(PREF_KEY_CHINESE_YEAR_LIST, chineseYearList.joinToString(SEPARATOR))
                .putString(PREF_KEY_CHINESE_MONTH_LIST, chineseMonthList.joinToString(SEPARATOR))
                .putString(PREF_KEY_CATEGORY_LIST, json.encodeToString(categoryDict))
                .apply()
            filterUpdateState = FilterUpdateState.COMPLETED
        }
    }

    private fun <T : QueryFilter> createFilter(prefKey: String, block: (Array<String>) -> T): T {
        val savedOptions = preferences.getString(prefKey, "")
        if (savedOptions.isNullOrEmpty()) {
            return block(emptyArray())
        }
        return block(savedOptions.split(SEPARATOR).toTypedArray())
    }

    private fun createCategoryFilters(): List<AnimeFilter<out Any>> {
        val result = mutableListOf<AnimeFilter<out Any>>(
            BroadMatchFilter(),
        )
        val savedCategories = preferences.getString(PREF_KEY_CATEGORY_LIST, "")
        if (savedCategories.isNullOrEmpty()) {
            return result
        }
        json.decodeFromString<Map<String, List<String>>>(savedCategories).forEach { (chineseCategory, chineseTags) ->
            // Get translated category name
            val categoryName = Tags.getTranslatedCategory(chineseCategory)
            // Create translated tag filters
            val tagFilters = chineseTags.map { chineseTag ->
                val tagName = Tags.getTranslatedTag(chineseTag)
                TagFilter("tags[]", tagName)
            }
            result.add(CategoryFilter(categoryName, tagFilters))
        }
        return result
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            createFilter(PREF_KEY_CHINESE_GENRE_LIST) { GenreFilter(it) },
            createFilter(PREF_KEY_CHINESE_SORT_LIST) { SortFilter(it) },
            DateFilter(
                createFilter(PREF_KEY_CHINESE_YEAR_LIST) { YearFilter(it) },
                createFilter(PREF_KEY_CHINESE_MONTH_LIST) { MonthFilter(it) },
            ),
            TagsFilter(createCategoryFilters()),
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.apply {
            // Language toggle (optional)
            addPreference(
                SwitchPreferenceCompat(context).apply {
                    key = PREF_KEY_USE_ENGLISH
                    title = "Use English filters"
                    summary = "Show filter names in English (also affects tags in anime details)"
                    setDefaultValue(true)
                },
            )
            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_VIDEO_QUALITY
                    title = "Preferred video quality"
                    entries = arrayOf("1080P", "720P", "480P")
                    entryValues = entries
                    setDefaultValue(DEFAULT_QUALITY)
                    summary =
                        "Current selection: ${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"
                    setOnPreferenceChangeListener { _, newValue ->
                        summary = "Current selection: ${newValue as String}"
                        true
                    }
                },
            )
            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_LANG
                    title = "Preferred language"
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
        const val PREF_KEY_USE_ENGLISH = "PREF_KEY_USE_ENGLISH"

        const val PREF_KEY_CHINESE_GENRE_LIST = "PREF_KEY_CHINESE_GENRE_LIST"
        const val PREF_KEY_CHINESE_SORT_LIST = "PREF_KEY_CHINESE_SORT_LIST"
        const val PREF_KEY_CHINESE_YEAR_LIST = "PREF_KEY_CHINESE_YEAR_LIST"
        const val PREF_KEY_CHINESE_MONTH_LIST = "PREF_KEY_CHINESE_MONTH_LIST"
        const val PREF_KEY_CATEGORY_LIST = "PREF_KEY_CATEGORY_LIST"

        const val DEFAULT_QUALITY = "1080P"
        const val SEPARATOR = "|||"
    }
}
