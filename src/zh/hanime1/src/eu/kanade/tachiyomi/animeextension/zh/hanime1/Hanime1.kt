package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
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
        get() = CloudflareHelper.BASE_URL
    override val lang: String
        get() = "zh"
    override val name: String
        get() = "Hanime1.me"
    override val supportsLatest: Boolean
        get() = true

    // CRITICAL: Use cloudflareClient instead of creating our own
    override val client = CloudflareHelper.createClient()
    
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val json by injectLazy<Json>()
    private var filterUpdateState = FilterUpdateState.NONE
    private val uploadDateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var filterUpdateJob: Job? = null

    // Add this function for proper error handling
    private suspend fun safeRequest(request: Request): Response {
        return try {
            val response = client.newCall(request).await()
            
            // Check response for blocks
            if (!response.isSuccessful || isBlockedResponse(response)) {
                throw CloudflareHelper.BlockedException(
                    CloudflareHelper.BlockInfo(
                        CloudflareHelper.BlockType.CLOUDFLARE,
                        "Access blocked (HTTP ${response.code})",
                        "Open Hanime1 in AniYomi WebView to solve Cloudflare"
                    )
                )
            }
            
            response
        } catch (e: CloudflareHelper.BlockedException) {
            // Re-throw Cloudflare blocks
            throw e
        } catch (e: Exception) {
            throw Exception("Network error: ${e.message}", e)
        }
    }
    
    private fun isBlockedResponse(response: Response): Boolean {
        return when (response.code) {
            403, 429, 503 -> true
            else -> {
                val contentType = response.header("Content-Type", "")
                if (contentType?.contains("text/html") == true) {
                    val body = response.peekBody(1024).string()
                    body.contains("Cloudflare", ignoreCase = true) ||
                    body.contains("verify you are human", ignoreCase = true) ||
                    body.contains("Checking your browser", ignoreCase = true)
                } else {
                    false
                }
            }
        }
    }

    private suspend fun safeParse(request: Request, selector: String = "div.search-doujin-videos"): Document {
        val response = safeRequest(request)
        val doc = response.asJsoup()
        
        // Check for Cloudflare/block in the document
        val blockInfo = CloudflareHelper.checkDocumentForBlock(doc, selector)
        if (blockInfo != null) {
            CloudflareHelper.saveBlockInfo(preferences, blockInfo)
            throw CloudflareHelper.BlockedException(blockInfo)
        }
        
        // Success - clear block status
        CloudflareHelper.clearBlockStatus(preferences)
        return doc
    }

    private fun cleanListTitle(rawTitle: String): String {
        return rawTitle
            .replace("""\s*[0-9:]+\s*""".toRegex(), "")
            .replace("""\s*\|\s*[0-9.]+萬次\s*""".toRegex(), "")
            .replace("""\s*\|\s*thumb_up\s*\d+%\s*\d+\s*""".toRegex(), "")
            .replace("""\u200B""".toRegex(), "")
            .trim()
    }

    private fun cleanEpisodeName(episodeName: String): String {
        return episodeName
            .replace("""\s*[0-9:]+\s*""".toRegex(), "")
            .replace("""\s*\|\s*.*""".toRegex(), "")
            .trim()
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val useEnglish = preferences.getBoolean(PREF_KEY_USE_ENGLISH, true)

        return SAnime.create().apply {
            title = ""
            val tags = doc.select(".single-video-tag").not("[data-toggle]").eachText()
            genre = if (useEnglish) {
                tags.map { chineseTag ->
                    Tags.getTranslatedTag(chineseTag) ?: chineseTag
                }.joinToString()
            } else {
                tags.joinToString()
            }

            author = doc.select("#video-artist-name").text()

            var originalTitle = ""
            doc.select("script[type=application/ld+json]").first()?.data()?.let {
                try {
                    val info = json.decodeFromString<JsonElement>(it).jsonObject
                    originalTitle = info["name"]!!.jsonPrimitive.content
                    description = info["description"]!!.jsonPrimitive.content
                    thumbnail_url = info["thumbnailUrl"]?.jsonArray?.get(0)?.jsonPrimitive?.content

                    val duration = doc.select(".video-duration, .duration")
                        .firstOrNull()?.text()?.trim()
                    title = if (!duration.isNullOrBlank()) {
                        "$originalTitle [$duration]"
                    } else {
                        originalTitle
                    }
                } catch (e: Exception) {
                    Log.e(name, "Failed to parse JSON-LD: ${e.message}")
                }
            }

            if (description.isNullOrBlank()) {
                description = doc.select("div.video-caption-text.caption-ellipsis")
                    .firstOrNull()
                    ?.text()
                    ?.trim()
                    ?: ""
            }

            if (title.isNullOrEmpty()) {
                val pageTitle = doc.select("h1, .title").firstOrNull()?.text() ?: ""
                val duration = doc.select(".video-duration, .duration")
                    .firstOrNull()?.text()?.trim()
                title = if (!duration.isNullOrBlank()) {
                    "$pageTitle [$duration]"
                } else {
                    pageTitle
                }
            }

            if (thumbnail_url.isNullOrEmpty()) {
                thumbnail_url = doc.select("meta[property=og:image]").attr("content").takeIf { it.isNotBlank() }
                    ?: doc.select(".single-video-thumbnail img").attr("src").takeIf { it.isNotBlank() }
                    ?: doc.select("img[src*=/thumbnail/]").attr("src").takeIf { it.isNotBlank() }
                    ?: ""
            }

            val type = doc.select("a#video-artist-name + a").text().trim()
            if (type == "裏番" || type == "泡麵番") {
                runBlocking {
                    try {
                        val cleanOriginal = cleanListTitle(originalTitle)
                        val cleanCurrent = cleanListTitle(title ?: "")
                        val cleanSearchTitle = cleanOriginal.takeIf { it.isNotBlank() }
                            ?: cleanCurrent.takeIf { it.isNotBlank() }
                            ?: ""

                        thumbnail_url = if (cleanSearchTitle.isNotBlank()) {
                            val animesPage = getSearchAnime(
                                1,
                                cleanSearchTitle,
                                AnimeFilterList(GenreFilter(arrayOf("", type)).apply { state = 1 }),
                            )
                            animesPage.animes.firstOrNull()?.thumbnail_url ?: thumbnail_url
                        } else {
                            thumbnail_url
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Failed to get bangumi cover image: ${e.message}")
                    }
                }
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("#playlist-scroll").first()?.select(">div") ?: return emptyList()
        val currentVideoTitle = jsoup.select("script[type=application/ld+json]").firstOrNull()?.data()?.let {
            try {
                val info = json.decodeFromString<JsonElement>(it).jsonObject
                info["name"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                null
            }
        }?.let { cleanEpisodeName(it) }
        var currentVideoDate: Long = 0L
        jsoup.select("script[type=application/ld+json]").first()?.data()?.let {
            try {
                val info = json.decodeFromString<JsonElement>(it).jsonObject
                info["uploadDate"]?.jsonPrimitive?.content?.let { date ->
                    currentVideoDate = runCatching {
                        uploadDateFormat.parse(date)?.time
                    }.getOrNull() ?: 0L
            }
            } catch (e: Exception) {
                Log.e(name, "Failed to parse upload date: ${e.message}")
            }
        }

        return nodes.mapIndexed { index, element ->
            SEpisode.create().apply {
                val href = element.select("a.overlay").attr("href")
                setUrlWithoutDomain(href)
                episode_number = (nodes.size - index).toFloat()

                val episodeTitle = element.select("div.card-mobile-title").text()
                val episodeDuration = element.select(".card-mobile-duration").firstOrNull {
                    it.text().contains(":")
                }?.text()?.trim()
                val episodeViews = element.select(".card-mobile-duration").firstOrNull {
                    it.text().contains("次")
                }?.text()?.trim()

                name = buildString {
                    append(episodeTitle.takeIf { it.isNotBlank() } ?: "Episode ${nodes.size - index}")
                    if (!episodeDuration.isNullOrBlank()) append(" [$episodeDuration]")
                    if (!episodeViews.isNullOrBlank()) append(" | $episodeViews")
                }

                val cleanEpisodeTitle = cleanEpisodeName(episodeTitle)
                if (currentVideoTitle != null && cleanEpisodeTitle == currentVideoTitle) {
                    date_upload = currentVideoDate
                }
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val sourceList = doc.select("video source")
        val preferQuality = preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)

        val videos = sourceList.mapNotNull {
            val quality = it.attr("size").takeIf { size -> size.isNotBlank() }
            val url = it.attr("src").takeIf { src -> src.isNotBlank() }
            if (url != null && quality != null) {
                Video(url, "${quality}P", videoUrl = url)
            } else {
                null
            }
        }.filterNot { it.videoUrl?.startsWith("blob") == true }

        return if (videos.isNotEmpty()) {
            videos.sortedByDescending { preferQuality == it.quality }
        } else {
            val videoUrl = doc.select("script[type=application/ld+json]").firstOrNull()?.data()?.let {
                try {
                    val info = json.decodeFromString<JsonElement>(it).jsonObject
                    info["contentUrl"]?.jsonPrimitive?.content
                } catch (e: Exception) {
                    null
                }
            } ?: ""

            if (videoUrl.isNotBlank()) {
                listOf(Video(videoUrl, "Raw", videoUrl = videoUrl))
            } else {
                emptyList()
            }
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        
        // Check for Cloudflare/block in the document
        val blockInfo = CloudflareHelper.checkDocumentForBlock(doc, "div.search-doujin-videos")
        if (blockInfo != null) {
            CloudflareHelper.saveBlockInfo(preferences, blockInfo)
            throw CloudflareHelper.BlockedException(blockInfo)
        }
        
        // Success - clear block status
        CloudflareHelper.clearBlockStatus(preferences)
        return searchAnimeParseFromDocument(doc)
    }

    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        
        // Check for Cloudflare/block in the document
        val blockInfo = CloudflareHelper.checkDocumentForBlock(doc, "div.search-doujin-videos")
        if (blockInfo != null) {
            CloudflareHelper.saveBlockInfo(preferences, blockInfo)
            throw CloudflareHelper.BlockedException(blockInfo)
        }
        
        // Success - clear block status
        CloudflareHelper.clearBlockStatus(preferences)
        return searchAnimeParseFromDocument(doc)
    }

    override fun popularAnimeRequest(page: Int): Request {
        val popularUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("sort", "本日排行")
        if (page > 1) {
            popularUrl.addQueryParameter("page", "$page")
        }
        return GET(popularUrl.build())
    }

    private fun String.appendInvisibleChar(): String {
        return "${this}\u200B"
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        
        // Check for Cloudflare/block in the document
        val blockInfo = CloudflareHelper.checkDocumentForBlock(jsoup, "div.search-doujin-videos")
        if (blockInfo != null) {
            CloudflareHelper.saveBlockInfo(preferences, blockInfo)
            throw CloudflareHelper.BlockedException(blockInfo)
        }
        
        // Success - clear block status
        CloudflareHelper.clearBlockStatus(preferences)
        return searchAnimeParseFromDocument(jsoup)
    }

    private fun searchAnimeParseFromDocument(jsoup: Document): AnimesPage {
        val nodes = jsoup.select("div.search-doujin-videos")

        val list = if (nodes.isNotEmpty()) {
            nodes.map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(element.select("a[class=overlay]").attr("href"))
                    thumbnail_url = element.select("img + img")
                        .firstOrNull()
                        ?.attr("src")
                        ?.takeIf { it.isNotBlank() }
                        ?: element.select("img")
                            .firstOrNull()
                            ?.attr("src")
                            .orEmpty()
                    val rawTitle = element.select("div.card-mobile-title").text()
                    title = cleanListTitle(rawTitle).appendInvisibleChar()
                    author = element.select(".card-mobile-user").text()
                }
            }
        } else {
            jsoup.select("a:not([target]) > .search-videos").map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(element.parent()!!.attr("href"))
                    thumbnail_url = element.select("img").attr("src")
                    val rawTitle = element.select(".home-rows-videos-title").text()
                    title = cleanListTitle(rawTitle).appendInvisibleChar()
                }
            }
        }

        val nextPage = jsoup.select("li.page-item a.page-link[rel=next]")
        return AnimesPage(list, nextPage.isNotEmpty())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // Check for previous blocks (optional - can be removed if you want fresh attempts each time)
        val lastBlock = CloudflareHelper.getLastBlockInfo(preferences)
        if (lastBlock != null) {
            // If blocked less than 5 minutes ago, throw exception
            if (System.currentTimeMillis() - lastBlock.timestamp < 5 * 60 * 1000L) {
                throw CloudflareHelper.BlockedException(lastBlock)
            }
        }

        val searchUrl = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
        val useEnglish = preferences.getBoolean(PREF_KEY_USE_ENGLISH, true)

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
                        val value = if (useEnglish) {
                            when (it.key) {
                                "genre" -> Tags.getOriginalGenre(it.selected) ?: it.selected
                                "sort" -> Tags.getOriginalSort(it.selected) ?: it.selected
                                "year" -> Tags.getOriginalYear(it.selected) ?: it.selected
                                "month" -> Tags.getOriginalMonth(it.selected) ?: it.selected
                                else -> it.selected
                            }
                        } else {
                            it.selected
                        }
                        searchUrl.addQueryParameter(it.key, value)
                    }
                }
                is BroadMatchFilter -> {
                    if (it.state) {
                        searchUrl.addQueryParameter(it.key, "on")
                    }
                }
                is TagFilter -> {
                    if (it.state) {
                        val tagValue = if (useEnglish) {
                            Tags.getOriginalTag(it.name) ?: it.name
                        } else {
                            it.name
                        }
                        searchUrl.addQueryParameter(it.key, tagValue)
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

    private fun updateFilters() {
        if (filterUpdateState == FilterUpdateState.UPDATING || filterUpdateJob?.isActive == true) {
            return
        }

        filterUpdateState = FilterUpdateState.UPDATING
        filterUpdateJob = coroutineScope.launch {
            try {
                val jsoup = client.newCall(GET("$baseUrl/search")).awaitSuccess().asJsoup()
                val genreList = jsoup.select("div.genre-option div.hentai-sort-options").eachText()
                val sortList = jsoup.select("div.hentai-sort-options-wrapper div.hentai-sort-options").eachText()
                val yearList = jsoup.select("select#year option").eachAttr("value")
                    .map { it.ifEmpty { "全部年份" } }
                val monthList = jsoup.select("select#month option").eachAttr("value")
                    .map { it.ifEmpty { "全部月份" } }

                val categoryDict = mutableMapOf<String, MutableList<String>>()
                var currentKey = ""
                jsoup.select("div#tags div.modal-body").first()?.children()?.forEach {
                    if (it.tagName() == "h5") {
                        currentKey = it.text()
                    }
                    if (it.tagName() == "label") {
                        if (currentKey in categoryDict) {
                            categoryDict[currentKey]
                        } else {
                            categoryDict[currentKey] = mutableListOf()
                            categoryDict[currentKey]
                        }!!.add(it.select("input[name]").attr("value"))
                    }
                }

                preferences.edit()
                    .putString(PREF_KEY_GENRE_LIST, genreList.joinToString(SEPARATOR))
                    .putString(PREF_KEY_SORT_LIST, sortList.joinToString(SEPARATOR))
                    .putString(PREF_KEY_YEAR_LIST, yearList.joinToString(SEPARATOR))
                    .putString(PREF_KEY_MONTH_LIST, monthList.joinToString(SEPARATOR))
                    .putString(PREF_KEY_CATEGORY_LIST, json.encodeToString(categoryDict))
                    .apply()
                filterUpdateState = FilterUpdateState.COMPLETED
            } catch (e: Exception) {
                Log.e(name, "Failed to update filters: ${e.message}")
                filterUpdateState = FilterUpdateState.FAILED
            }
        }
    }

    private fun <T : QueryFilter> createFilter(prefKey: String, block: (Array<String>) -> T): T {
        val savedOptions = preferences.getString(prefKey, "")
        return if (savedOptions.isNullOrEmpty()) {
            block(emptyArray())
        } else {
            block(savedOptions.split(SEPARATOR).toTypedArray())
        }
    }

    private fun createCategoryFilters(): List<AnimeFilter<out Any>> {
        val result = mutableListOf<AnimeFilter<out Any>>(
            BroadMatchFilter(),
        )
        val useEnglish = preferences.getBoolean(PREF_KEY_USE_ENGLISH, true)

        val savedCategories = preferences.getString(PREF_KEY_CATEGORY_LIST, "")
        if (savedCategories.isNullOrEmpty()) {
            return result
        }

        try {
            json.decodeFromString<Map<String, List<String>>>(savedCategories).forEach { (chineseCategory, chineseTags) ->
                val categoryName = if (useEnglish) {
                    Tags.getTranslatedCategory(chineseCategory) ?: chineseCategory
                } else {
                    chineseCategory
                }

                val tagFilters = chineseTags.map { chineseTag ->
                    val tagName = if (useEnglish) {
                        Tags.getTranslatedTag(chineseTag) ?: chineseTag
                    } else {
                        chineseTag
                    }
                    TagFilter("tags[]", tagName)
                }
                result.add(CategoryFilter(categoryName, tagFilters))
            }
        } catch (e: Exception) {
            Log.e(name, "Failed to create category filters: ${e.message}")
        }

        return result
    }

    override fun getFilterList(): AnimeFilterList {
        if (filterUpdateState == FilterUpdateState.NONE) {
            updateFilters()
        }

        val useEnglish = preferences.getBoolean(PREF_KEY_USE_ENGLISH, true)

        val genreFilter = createFilter(PREF_KEY_GENRE_LIST) { GenreFilter(it) }
        val sortFilter = createFilter(PREF_KEY_SORT_LIST) { SortFilter(it) }
        val yearFilter = createFilter(PREF_KEY_YEAR_LIST) { YearFilter(it) }
        val monthFilter = createFilter(PREF_KEY_MONTH_LIST) { MonthFilter(it) }

        return if (useEnglish) {
            val translatedGenreValues = genreFilter.values.map {
                Tags.getTranslatedGenre(it) ?: it
            }.toTypedArray()
            val translatedSortValues = sortFilter.values.map {
                Tags.getTranslatedSort(it) ?: it
            }.toTypedArray()
            val translatedYearValues = yearFilter.values.map {
                Tags.getTranslatedYear(it) ?: it
            }.toTypedArray()
            val translatedMonthValues = monthFilter.values.map {
                Tags.getTranslatedMonth(it) ?: it
            }.toTypedArray()
            AnimeFilterList(
                GenreFilter(translatedGenreValues),
                SortFilter(translatedSortValues),
                DateFilter(
                    YearFilter(translatedYearValues),
                    MonthFilter(translatedMonthValues),
                ),
                TagsFilter(createCategoryFilters()),
            )
        } else {
            AnimeFilterList(
                genreFilter,
                sortFilter,
                DateFilter(yearFilter, monthFilter),
                TagsFilter(createCategoryFilters()),
            )
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        val statusHeader = Preference().apply {
            key = "status_header"
            title = "🔍 Connection Status"
        }
        screen.addPreference(statusHeader)

        val connectionStatus = Preference().apply {
            key = "connection_status"
            title = "🌐 Connection Status"
            summary = "Tap 'Test Connection' to check"
        }
        screen.addPreference(connectionStatus)

        val englishFilter = SwitchPreferenceCompat(context).apply {
            key = PREF_KEY_USE_ENGLISH
            title = "🌐 Use English filters"
            summary = "Show filter names in English (also affects tags in anime details)"
            setDefaultValue(true)
        }
        screen.addPreference(englishFilter)

        val cookieHeader = Preference().apply {
            key = "cookie_header"
            title = "🔑 Cookie Management"
        }
        screen.addPreference(cookieHeader)

        val importCookies = EditTextPreference(context).apply {
            key = PREF_KEY_IMPORTED_COOKIES
            title = "📥 Import Cookies"
            summary = "Paste cookies from browser/WebView"
            dialogTitle = "Import Cookies"
            dialogMessage =
                "1. Open Hanime1 in WebView/browser\n" +
                "2. Log in/complete any CAPTCHA\n" +
                "3. Export cookies (use browser extension)\n" +
                "4. Paste here\n\n" +
                "Format: JSON array or raw cookies"

            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim()
                preferences.edit()
                    .putBoolean(PREF_KEY_COOKIE_INVALID, false)
                    .apply()

                summary =
                    if (value.isNotEmpty()) {
                        try {
                            val cookies = CloudflareHelper.parseCookies(value)
                            "✅ ${cookies.size} cookie(s) imported"
                        } catch (e: Exception) {
                            "⚠ Failed to import cookies: ${e.message}"
                        }
                    } else {
                        "⚠ No cookies - Import required"
                    }
                true
            }
        }
        screen.addPreference(importCookies)

        val customUa = EditTextPreference(context).apply {
            key = PREF_KEY_CUSTOM_UA
            title = "🖥️ Custom User-Agent"
            summary = "Optional: Use desktop browser UA"
            dialogTitle = "Desktop User-Agent"
            dialogMessage =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

            setOnPreferenceChangeListener { _, newValue ->
                summary = (newValue as String).ifBlank {
                    "Using default desktop User-Agent"
                }
                true
            }
        }
        screen.addPreference(customUa)

        val videoHeader = Preference().apply {
            key = "video_header"
            title = "🎥 Video Settings"
        }
        screen.addPreference(videoHeader)

        val videoQuality = ListPreference(context).apply {
            key = PREF_KEY_VIDEO_QUALITY
            title = "Preferred Quality"
            entries = arrayOf("1080P", "720P", "480P")
            entryValues = entries
            setDefaultValue(DEFAULT_QUALITY)
            summary =
                "Current: ${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"

            setOnPreferenceChangeListener { _, newValue ->
                summary = "Current: ${newValue as String}"
                true
            }
        }
        screen.addPreference(videoQuality)

        val languagePref = ListPreference(context).apply {
            key = PREF_KEY_LANG
            title = "Preferred Language"
            summary = "Affects video subtitles"
            entries = arrayOf("繁體中文", "簡體中文")
            entryValues = arrayOf("zh-CHT", "zh-CHS")

            setOnPreferenceChangeListener { _, newValue ->
                CloudflareHelper.setLanguageCookie(newValue as String)
                true
            }
        }
        screen.addPreference(languagePref)

        val helpHeader = Preference().apply {
            key = "help_header"
            title = "❓ Help & Troubleshooting"
        }
        screen.addPreference(helpHeader)

        val showHelp = Preference().apply {
            key = "show_help"
            title = "📖 View Help Guide"
            summary = "Common issues and solutions"

            setOnPreferenceClickListener {
                showHelpDialog(context)
                true
            }
        }
        screen.addPreference(showHelp)

        val testConnection = Preference().apply {
            key = "test_connection"
            title = "🔧 Test Connection"
            summary = "Check if extension can access Hanime1"
            
            setOnPreferenceClickListener {
                coroutineScope.launch {
                    try {
                        val testUrl = "$baseUrl/search"
                        val request = GET(testUrl)
                        val response = safeRequest(request)
                        android.app.AlertDialog.Builder(context)
                            .setTitle("✅ Connection Successful")
                            .setMessage("Successfully connected to Hanime1")
                            .setPositiveButton("OK", null)
                            .show()
                    } catch (e: CloudflareHelper.BlockedException) {
                        android.app.AlertDialog.Builder(context)
                            .setTitle("⚠️ Cloudflare Detected")
                            .setMessage("${e.message}\n\nOpen Hanime1 in WebView to solve.")
                            .setPositiveButton("OK", null)
                            .show()
                    } catch (e: Exception) {
                        android.app.AlertDialog.Builder(context)
                            .setTitle("❌ Connection Failed")
                            .setMessage("Failed to connect: ${e.message}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
                true
            }
        }
        screen.addPreference(testConnection)
    }

    private fun showHelpDialog(context: Context) {
        val helpText = """
            ℹ️ **Hanime1 Extension Help**
            
            **Common Issues & Solutions:**
            
            1. **Cloudflare Blocked (403/503)**
               - Open Hanime1 in AniYomi WebView
               - Complete any CAPTCHA/verification
               - The app will handle cookies automatically
            
            2. **Age Verification Required**
               - Visit hanime1.me in browser first
               - Complete age verification
               - Then use the extension
            
            3. **Rate Limited (429)**
               - Wait 5-10 minutes
               - Avoid rapid searches
               - Use Popular/Latest tabs
            
            4. **Content Not Loading**
               - Try clearing app cache
               - Restart AniYomi
               - Update extension
            
            **Tips:**
            • Let AniYomi handle Cloudflare automatically
            • Use English filters if Chinese fails
            • Try 'Broad Match' in search filters
            • Check network connection
            
            **Need More Help?**
            Contact extension maintainer or check AniYomi Discord.
        """.trimIndent()
        
        android.app.AlertDialog.Builder(context)
            .setTitle("Hanime1 Extension Help")
            .setMessage(helpText)
            .setPositiveButton("Got it", null)
            .show()
    }

    companion object {
        const val PREF_KEY_VIDEO_QUALITY = "PREF_KEY_VIDEO_QUALITY"
        const val PREF_KEY_LANG = "PREF_KEY_LANG"
        const val PREF_KEY_USE_ENGLISH = "PREF_KEY_USE_ENGLISH"
        const val PREF_KEY_GENRE_LIST = "PREF_KEY_GENRE_LIST"
        const val PREF_KEY_SORT_LIST = "PREF_KEY_SORT_LIST"
        const val PREF_KEY_YEAR_LIST = "PREF_KEY_YEAR_LIST"
        const val PREF_KEY_MONTH_LIST = "PREF_KEY_MONTH_LIST"
        const val PREF_KEY_CATEGORY_LIST = "PREF_KEY_CATEGORY_LIST"
        const val PREF_KEY_IMPORTED_COOKIES = "PREF_KEY_IMPORTED_COOKIES"
        const val PREF_KEY_CUSTOM_UA = "PREF_KEY_CUSTOM_UA"
        const val PREF_KEY_COOKIE_INVALID = "PREF_KEY_COOKIE_INVALID"
        const val DEFAULT_QUALITY = "1080P"
        const val SEPARATOR = "|||"
    }
}
