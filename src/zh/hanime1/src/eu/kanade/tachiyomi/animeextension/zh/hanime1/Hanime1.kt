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
        val blocked = CloudflareHelper.checkAndHandleBlock(response, doc, "div.search-doujin-videos", preferences)
        return if (blocked) {
            AnimesPage(emptyList(), false)
        } else {
            searchAnimeParseFromDocument(doc)
        }
    }

    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val blocked = CloudflareHelper.checkAndHandleBlock(response, doc, "div.search-doujin-videos", preferences)
        return if (blocked) {
            AnimesPage(emptyList(), false)
        } else {
            searchAnimeParseFromDocument(doc)
        }
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
        if (CloudflareHelper.checkAndHandleBlock(response, jsoup, "div.search-doujin-videos", preferences)) {
            val blockInfo = CloudflareHelper.getLastBlockInfo(preferences)
            throw Exception(
                "🔒 Access Blocked\n\nIssue: ${blockInfo?.message ?: "Cloudflare protection"}\n\n" +
                    "Solution: ${blockInfo?.solution ?: "Please re-import fresh cookies"}\n\n" +
                    "⚠️ How to fix:\n1. Open Hanime1 in WebView\n2. Log in/complete verification\n3. Import cookies\n4. Retry",
            )
        }
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
        if (CloudflareHelper.isBlocked(preferences)) {
            val blockInfo = CloudflareHelper.getLastBlockInfo(preferences)
            throw Exception(
                "⚠️ Access Blocked\n\nReason: ${blockInfo?.message ?: "Cloudflare protection"}\n\n" +
                    "Steps to fix:\n1. Go to Extension Settings\n2. Clear Cookies\n3. Import fresh cookies\n4. Retry search",
            )
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
    
        val statusHeader = Preference(context)
        statusHeader.key = "status_header"
        statusHeader.title = "🔍 Connection Status"
        screen.addPreference(statusHeader)

        val cookieStatus = Preference(context)
        cookieStatus.key = "cookie_status_detailed"
        cookieStatus.title = "Current Status"
        cookieStatus.summary = CloudflareHelper.getCookieStatus(preferences)
        screen.addPreference(cookieStatus)

        val blockHistory = Preference(context)
        blockHistory.key = "block_history"
        blockHistory.title = "Recent Blocks"
        val history = CloudflareHelper.getBlockHistory()
        blockHistory.summary = if (history.isEmpty()) "No recent blocks" else "${history.size} block(s) - Tap to view"
        blockHistory.setOnPreferenceClickListener {
            showBlockHistoryDialog(context)
            true
        }
        screen.addPreference(blockHistory)

        val englishFilter = SwitchPreferenceCompat(context)
        englishFilter.key = PREF_KEY_USE_ENGLISH
        englishFilter.title = "🌐 Use English filters"
        englishFilter.summary = "Show filter names in English (also affects tags in anime details)"
        englishFilter.setDefaultValue(true)
        screen.addPreference(englishFilter)

        val cookieHeader = Preference(context)
        cookieHeader.key = "cookie_header"
        cookieHeader.title = "🔑 Cookie Management"
        screen.addPreference(cookieHeader)

        val clearCookies = Preference(context)
        clearCookies.key = "clear_cookies"
        clearCookies.title = "🗑️ Clear All Cookies"
        clearCookies.summary = "Clear current cookies before importing fresh ones"
        clearCookies.setOnPreferenceClickListener {
            CloudflareHelper.clearAllCookies(preferences)
            clearCookies.summary = "Cookies cleared - Ready for fresh import"
            true
        }
        screen.addPreference(clearCookies)

        val importCookies = EditTextPreference(context)
        importCookies.key = PREF_KEY_IMPORTED_COOKIES
        importCookies.title = "📥 Import Cookies"
        importCookies.summary = "Paste cookies from browser/WebView"
        importCookies.dialogTitle = "Import Cookies"
        importCookies.dialogMessage = "1. Open Hanime1 in WebView/browser\n2. Log in/complete any CAPTCHA\n3. Export cookies (use browser extension)\n4. Paste here\n\nFormat: JSON array or raw cookies"
        importCookies.setOnPreferenceChangeListener { _, newValue ->
            val value = (newValue as String).trim()
            preferences.edit()
                .putBoolean(PREF_KEY_COOKIE_INVALID, false)
                .apply()

            importCookies.summary = if (value.isNotEmpty()) {
                val cookies = CloudflareHelper.parseCookies(value)
                "✅ ${cookies.size} cookie(s) imported"
            } else {
                "⚠ No cookies - Import required"
            }
            true
        }
        screen.addPreference(importCookies)

        val customUa = EditTextPreference(context)
        customUa.key = PREF_KEY_CUSTOM_UA
        customUa.title = "🖥️ Custom User-Agent"
        customUa.summary = "Optional: Use desktop browser UA"
        customUa.dialogTitle = "Desktop User-Agent"
        customUa.dialogMessage = "Recommended for better compatibility:\n\nMozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        customUa.setOnPreferenceChangeListener { _, newValue ->
            customUa.summary = (newValue as String).ifBlank {
                "Using default desktop User-Agent"
            }
            true
        }
        screen.addPreference(customUa)

        val videoHeader = Preference(context)
        videoHeader.key = "video_header"
        videoHeader.title = "🎥 Video Settings"
        screen.addPreference(videoHeader)

        val videoQuality = ListPreference(context)
        videoQuality.key = PREF_KEY_VIDEO_QUALITY
        videoQuality.title = "Preferred Quality"
        videoQuality.entries = arrayOf("1080P", "720P", "480P")
        videoQuality.entryValues = arrayOf("1080P", "720P", "480P")
        videoQuality.setDefaultValue(DEFAULT_QUALITY)
        videoQuality.summary = "Current: ${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"
        videoQuality.setOnPreferenceChangeListener { _, newValue ->
            videoQuality.summary = "Current: ${newValue as String}"
            true
        }
        screen.addPreference(videoQuality)

        val languagePref = ListPreference(context)
        languagePref.key = PREF_KEY_LANG
        languagePref.title = "Preferred Language"
        languagePref.summary = "Affects video subtitles"
        languagePref.entries = arrayOf("繁體中文", "簡體中文")
        languagePref.entryValues = arrayOf("zh-CHT", "zh-CHS")
        languagePref.setOnPreferenceChangeListener { _, newValue ->
            CloudflareHelper.setLanguageCookie(newValue as String)
            true
        }
        screen.addPreference(languagePref)

        val helpHeader = Preference(context)
        helpHeader.key = "help_header"
        helpHeader.title = "❓ Help & Troubleshooting"
        screen.addPreference(helpHeader)

        val showHelp = Preference(context)
        showHelp.key = "show_help"
        showHelp.title = "📖 View Help Guide"
        showHelp.summary = "Common issues and solutions"
        showHelp.setOnPreferenceClickListener {
            showHelpDialog(context)
            true
        }
        screen.addPreference(showHelp)

        val testConnection = Preference(context)
        testConnection.key = "test_connection"
        testConnection.title = "🔧 Test Connection"
        testConnection.summary = "Check if extension can access Hanime1"
        testConnection.setOnPreferenceClickListener {
            testConnection()
            true
        }
        screen.addPreference(testConnection)
    }

    private fun showBlockHistoryDialog(context: Context) {
        val history = CloudflareHelper.formatBlockHistory()
        android.app.AlertDialog.Builder(context)
            .setTitle("Recent Blocks")
            .setMessage(history)
            .setPositiveButton("Clear History") { _, _ ->
                CloudflareHelper.clearBlockHistory()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHelpDialog(context: Context) {
        val helpText = CloudflareHelper.getDetailedHelp(context)
        android.app.AlertDialog.Builder(context)
            .setTitle("Hanime1 Extension Help")
            .setMessage(helpText)
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun testConnection() {
        coroutineScope.launch {
            try {
                val testUrl = "$baseUrl/search"
                val request = GET(testUrl)
                val response = client.newCall(request).execute()
                val doc = response.asJsoup()

                CloudflareHelper.checkAndHandleBlock(response, doc, "div.search-doujin-videos", preferences)
            } catch (e: Exception) {
                Log.e("Hanime1", "Connection test failed: ${e.message}")
            }
        }
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
