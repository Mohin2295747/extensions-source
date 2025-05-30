package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import kotlin.coroutines.resume

class ChineseTranslator(private val context: Context) {
    private var translator: com.google.mlkit.nl.translate.Translator? = null
    private val languageIdentifier = LanguageIdentification.getClient()
    private val translationCache = object : LinkedHashMap<String, String>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }

    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text

        translationCache[text]?.let { return@withContext it }

        val detectedLang = detectLanguage(text)
        if (detectedLang != "zh" && detectedLang != "zh-CN" && detectedLang != "zh-TW") {
            return@withContext text
        }

        if (translator == null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            translator = Translation.getClient(options)

            try {
                translator!!.downloadModelIfNeeded().await()
            } catch (e: Exception) {
                return@withContext text
            }
        }

        return@withContext try {
            val translated = translator!!.translate(text).await()
            translationCache[text] = translated
            translated
        } catch (e: Exception) {
            text
        }
    }

    private suspend fun detectLanguage(text: String): String {
        return suspendCancellableCoroutine { continuation ->
            val task = languageIdentifier.identifyLanguage(text)
            task.addOnSuccessListener { languageCode ->
                continuation.resume(languageCode)
            }
            task.addOnFailureListener {
                continuation.resume("und")
            }
        }
    }

    fun cleanup() {
        try {
            translator?.close()
        } finally {
            languageIdentifier.close()
        }
    }
}

class Hanime1 : AnimeHttpSource(), ConfigurableAnimeSource {
    private val translator by lazy {
        ChineseTranslator(Injekt.get<Application>().applicationContext)
    }

    override val name = "Hanime1"
    override val baseUrl = "https://hanime1.me"
    override val lang = "zh"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        val translatePref = ListPreference(screen.context).apply {
            key = PREF_TRANSLATE_KEY
            title = "Translation"
            entries = arrayOf("Disabled", "English")
            entryValues = arrayOf("false", "true")
            summary = "%s"
            setDefaultValue("false")
        }

        screen.addPreference(translatePref)
    }

    companion object {
        private const val PREF_TRANSLATE_KEY = "pref_translate_enabled"
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            SortFilter(translator),
            GenreFilter(translator),
            DateFilter(
                translator,
                YearFilter(translator),
                MonthFilter(translator)
            ),
            CategoryFilter(
                runBlocking { translator.translate("分類") },
                listOf(
                    HotFilter(translator),
                    BroadMatchFilter(translator)
                )
            )
        )
    }

    private suspend fun translateIfEnabled(text: String): String {
        val prefs = Injekt.get<SharedPreferences>()
        return if (prefs.getBoolean(PREF_TRANSLATE_KEY, false)) {
            translator.translate(text)
        } else {
            text
        }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(popularAnimeRequest(page)).awaitSuccess()
        return popularAnimeParse(response)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(latestUpdatesRequest(page)).awaitSuccess()
        return latestUpdatesParse(response)
    }

    override suspend fun searchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        return searchAnimeParse(response)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        return animeDetailsParse(response)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(episodeListRequest(anime)).awaitSuccess()
        return episodeListParse(response)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).awaitSuccess()
        return videoListParse(response)
    }

    // Request builders
    private fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/ranking?page=$page", headers)
    }

    private fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest?page=$page", headers)
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
            .apply {
                filters.forEach { filter ->
                    when (filter) {
                        is QueryFilter -> addQueryParameter(filter.key, filter.selected)
                        is TagFilter -> if (filter.state) addQueryParameter(filter.key, "true")
                    }
                }
            }
            .build()
        return GET(url, headers)
    }

    private fun animeDetailsRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers)
    }

    private fun episodeListRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers)
    }

    private fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    // Parsers
    override suspend fun popularAnimeParse(response: Response): AnimesPage {
        return searchAnimeParse(response)
    }

    override suspend fun latestUpdatesParse(response: Response): AnimesPage {
        return searchAnimeParse(response)
    }

    override suspend fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("div.search-doujin-videos.hidden-xs")
        val list = if (nodes.isNotEmpty()) {
            nodes.map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.select("a[class=overlay]").attr("href"))
                    thumbnail_url = it.select("img + img").attr("src")

                    title = runBlocking {
                        translateIfEnabled(it.select("div.card-mobile-title").text())
                    }.appendInvisibleChar()

                    author = runBlocking {
                        translateIfEnabled(it.select(".card-mobile-user").text())
                    }
                }
            }
        } else {
            jsoup.select("a:not([target]) > .search-videos").map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.parent()!!.attr("href"))
                    thumbnail_url = it.select("img").attr("src")

                    title = runBlocking {
                        translateIfEnabled(it.select(".home-rows-videos-title").text())
                    }.appendInvisibleChar()
                }
            }
        }
        val nextPage = jsoup.select("li.page-item a.page-link[rel=next]")
        return AnimesPage(list, nextPage.isNotEmpty())
    }

    override suspend fun animeDetailsParse(response: Response): SAnime {
        val jsoup = response.asJsoup()
        return SAnime.create().apply {
            genre = jsoup.select(".single-video-tag")
                .not("[data-toggle]")
                .eachText()
                .joinToString { runBlocking { translateIfEnabled(it) } }

            author = runBlocking {
                translateIfEnabled(jsoup.select("#video-artist-name").text())
            }

            jsoup.select("script[type=application/ld+json]").first()?.data()?.let {
                val info = json.decodeFromString<JsonElement>(it).jsonObject

                title = runBlocking {
                    translateIfEnabled(info["name"]!!.jsonPrimitive.content)
                }

                description = runBlocking {
                    translateIfEnabled(info["description"]!!.jsonPrimitive.content)
                }
            }
        }
    }

    override suspend fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        return jsoup.select(".episode-list a").map {
            SEpisode.create().apply {
                name = runBlocking {
                    translateIfEnabled(it.select(".episode-title").text())
                }
                episode_number = it.select(".episode-number").text().toFloatOrNull() ?: 0f
                date_upload = parseDate(it.select(".episode-date").text())
                setUrlWithoutDomain(it.attr("href"))
            }
        }
    }

    override suspend fun videoListParse(response: Response): List<Video> {
        // Implementation depends on the video extraction method
        // This is just a placeholder
        return emptyList()
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun String.appendInvisibleChar(): String {
        return "$this\u200B" // Zero-width space to prevent title truncation
    }
}
