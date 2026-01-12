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

    private fun cleanListTitle(rawTitle: String): String {
        return rawTitle
            .replace("""\u200B""".toRegex(), "")
            .trim()
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            val titleRaw = doc.select("h1, .title").firstOrNull()?.text() ?: ""
            title = cleanListTitle(titleRaw)
            thumbnail_url = doc.select("img + img").firstOrNull()?.attr("src").orEmpty()
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("#playlist-scroll").first()?.select(">div") ?: return emptyList()
        return nodes.mapIndexed { index, element ->
            SEpisode.create().apply {
                val href = element.select("a.overlay").attr("href")
                setUrlWithoutDomain(href)
                episode_number = (nodes.size - index).toFloat()
                name = element.select("div.card-mobile-title").text().takeIf { it.isNotBlank() }
                    ?: "Episode ${nodes.size - index}"
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
            if (url != null && quality != null) Video(url, "${quality}P", videoUrl = url) else null
        }.filterNot { it.videoUrl?.startsWith("blob") == true }

        return if (videos.isNotEmpty()) {
            videos.sortedByDescending { preferQuality == it.quality }
        } else emptyList()
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)
    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())
    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int): Request {
        val popularUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("sort", "本日排行")
        if (page > 1) popularUrl.addQueryParameter("page", "$page")
        return GET(popularUrl.build())
    }

    private fun String.appendInvisibleChar(): String = "${this}\u200B"

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("div.search-doujin-videos.hidden-xs:not(:has(a[target=_blank]))")
        val list = nodes.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.select("a[class=overlay]").attr("href"))
                thumbnail_url = element.select("img + img").firstOrNull()?.attr("src").orEmpty()
                val rawTitle = element.select("div.card-mobile-title").text()
                title = cleanListTitle(rawTitle).appendInvisibleChar()
                author = element.select(".card-mobile-user").text()
            }
        }

        val nextPage = jsoup.select("li.page-item a.page-link[rel=next]")
        return AnimesPage(list, nextPage.isNotEmpty())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchUrl = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
        if (query.isNotEmpty()) searchUrl.addQueryParameter("query", query)
        if (page > 1) searchUrl.addQueryParameter("page", "$page")
        return GET(searchUrl.build())
    }

    private fun checkFiltersInterceptor(chain: Interceptor.Chain): Response {
        if (filterUpdateState == FilterUpdateState.NONE) updateFilters()
        return chain.proceed(chain.request())
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun updateFilters() {
        filterUpdateState = FilterUpdateState.UPDATING
        val exceptionHandler = CoroutineExceptionHandler { _, _ -> filterUpdateState = FilterUpdateState.FAILED }
        GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
            try {
                val jsoup = client.newCall(GET("$baseUrl/search")).awaitSuccess().asJsoup()
                val genreList = jsoup.select("div.genre-option div.hentai-sort-options").eachText()
                val sortList = jsoup.select("div.hentai-sort-options-wrapper div.hentai-sort-options").eachText()
                preferences.edit()
                    .putString(PREF_KEY_GENRE_LIST, genreList.joinToString(SEPARATOR))
                    .putString(PREF_KEY_SORT_LIST, sortList.joinToString(SEPARATOR))
                    .apply()
                filterUpdateState = FilterUpdateState.COMPLETED
            } catch (e: Exception) {
                Log.e(name, "Failed to update filters: ${e.message}")
                filterUpdateState = FilterUpdateState.FAILED
            }
        }
    }

    override fun getFilterList(): AnimeFilterList {
        val genreFilter = createFilter(PREF_KEY_GENRE_LIST) { GenreFilter(it) }
        val sortFilter = createFilter(PREF_KEY_SORT_LIST) { SortFilter(it) }
        return AnimeFilterList(genreFilter, sortFilter)
    }

    private fun <T : QueryFilter> createFilter(prefKey: String, block: (Array<String>) -> T): T {
        val savedOptions = preferences.getString(prefKey, "")
        return if (savedOptions.isNullOrEmpty()) block(emptyArray()) else block(savedOptions.split(SEPARATOR).toTypedArray())
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.apply {
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
                    title = "設置首選畫質"
                    entries = arrayOf("1080P", "720P", "480P")
                    entryValues = entries
                    setDefaultValue(DEFAULT_QUALITY)
                    summary = "當前選擇：${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"
                },
            )
        }
    }

    companion object {
        const val PREF_KEY_VIDEO_QUALITY = "PREF_KEY_VIDEO_QUALITY"
        const val PREF_KEY_USE_ENGLISH = "PREF_KEY_USE_ENGLISH"
        const val PREF_KEY_GENRE_LIST = "PREF_KEY_GENRE_LIST"
        const val PREF_KEY_SORT_LIST = "PREF_KEY_SORT_LIST"
        const val DEFAULT_QUALITY = "1080P"
        const val SEPARATOR = "|||"
    }
}
