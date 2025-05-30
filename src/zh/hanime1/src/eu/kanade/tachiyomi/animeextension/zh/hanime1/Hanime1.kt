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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.api.get

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
    override val name = "Hanime1"
    override val baseUrl = "https://hanime1.me"
    override val lang = "zh"
    override val supportsLatest = true

    private val translator by lazy {
        ChineseTranslator(Injekt.get<Application>().applicationContext)
    }

    private val json by lazy { Json { ignoreUnknownKeys = true } }

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

    private suspend fun translateIfEnabled(text: String): String {
        val prefs = Injekt.get<SharedPreferences>()
        return if (prefs.getBoolean(PREF_TRANSLATE_KEY, false)) {
            translator.translate(text)
        } else {
            text
        }
    }
    override suspend fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/ranking?type=weekly&page=$page")
    }

    override suspend fun popularAnimeParse(response: Response): AnimesPage {
        return parseAnimeList(response)
    }
    override suspend fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest?page=$page")
    }

    override suspend fun latestUpdatesParse(response: Response): AnimesPage {
        return parseAnimeList(response)
    }
    override suspend fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("query", query)
            addQueryParameter("page", page.toString())
            filters.forEach { filter ->
                when (filter) {
                    is QueryFilter -> {
                        if (filter.selected.isNotEmpty()) {
                            addQueryParameter(filter.key, filter.selected)
                        }
                    }
                    is TagFilter -> {
                        if (filter.state) {
                            addQueryParameter(filter.key, "true")
                        }
                    }
                }
            }
        }.build()
        return GET(url.toString())
    }
    override suspend fun searchAnimeParse(response: Response): AnimesPage {
        return parseAnimeList(response)
    }
    private suspend fun parseAnimeList(response: Response): AnimesPage {
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
    override suspend fun animeDetailsRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url)
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
    override suspend fun episodeListRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url)
    }
     override suspend fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        return jsoup.select(".playlist-episode").map {
            SEpisode.create().apply {
                name = runBlocking {
                    translateIfEnabled(it.select(".playlist-episode-title").text())
                }
                episode_number = it.select(".playlist-episode-number").text().toFloatOrNull() ?: 0f
                setUrlWithoutDomain(it.attr("href"))
            }
        }
    }
    override suspend fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url)
    }

    override suspend fun videoListParse(response: Response): List<Video> {
        val jsoup = response.asJsoup()
        return jsoup.select("source").map {
            Video(
                url = it.attr("src"),
                quality = it.attr("title"),
                videoUrl = it.attr("src"),
            )
        }
    }
    override fun videoUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }
    override fun getFilterList(): AnimeFilterList {
        val translator = ChineseTranslator(Injekt.get<Application>().applicationContext)
        return AnimeFilterList(
            SortFilter(translator),
            GenreFilter(translator),
            DateFilter(
                translator,
                YearFilter(translator),
                MonthFilter(translator),
            ),
            HotFilter(translator),
        )
    }

    private fun String.appendInvisibleChar(): String {
        return "$this\u200B"
    }
}
