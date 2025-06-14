package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Hanime1 : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Hanime1.me"
    override val baseUrl = "https://hanime1.me"
    override val lang = "zh"
    override val supportsLatest = true

    private val network by injectLazy<eu.kanade.tachiyomi.network.NetworkHelper>()
    override val client = network.client

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Context>().getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }

    private val json by lazy { Json { ignoreUnknownKeys = true } }

    private fun translateText(text: String): String {
        return try {
            val jsonBody = JSONObject().put("inputs", text).toString()
            val body = jsonBody.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api-inference.huggingface.co/models/Helsinki-NLP/opus-mt-zh-en")
                .post(body)
                .addHeader("Authorization", "Bearer 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")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val resp = JSONArray(response.body?.string() ?: "[]").getJSONObject(0)
                resp.optString("translation_text", text)
            } else {
                text
            }
        } catch (e: Exception) {
            text
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val jsoup = response.asJsoup()
        return SAnime.create().apply {
            genre = jsoup.select(".single-video-tag").not("[data-toggle]").eachText().joinToString()
            author = jsoup.select("#video-artist-name").text()
            jsoup.select("script[type=application/ld+json]").firstOrNull()?.data()?.let {
                val info = json.decodeFromJsonElement<JsonObject>(json.parseToJsonElement(it))
                val rawTitle = info["name"]!!.jsonPrimitive.content
                val rawDesc = info["description"]!!.jsonPrimitive.content
                title = translateText(rawTitle)
                description = translateText(rawDesc)
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("#playlist-scroll > div")
        return nodes.mapIndexed { index, element ->
            SEpisode.create().apply {
                val href = element.select("a.overlay").attr("href")
                url = href
                episode_number = (nodes.size - index).toFloat()
                name = element.select("div.card-mobile-title").text()
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val sourceList = doc.select("video source")
        val preferQuality = preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)
        return sourceList.map { source ->
            val quality = source.attr("size")
            val url = source.attr("src")
            Video(url, "${quality}P", url)
        }.filterNot { it.url.startsWith("blob") }
            .sortedByDescending { it.quality == preferQuality }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)
    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())
    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)
    override fun popularAnimeRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        val list = jsoup.select("div.search-doujin-videos.hidden-xs").map {
            SAnime.create().apply {
                url = it.select("a.overlay").attr("href")
                thumbnail_url = it.select("img + img").attr("src")
                title = translateText(it.select("div.card-mobile-title").text())
                author = it.select(".card-mobile-user").text()
            }
        }
        val hasNext = jsoup.select("li.page-item a.page-link[rel=next]").isNotEmpty()
        return AnimesPage(list, hasNext)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("query", query)
        if (page > 1) urlBuilder.addQueryParameter("page", page.toString())
        return GET(urlBuilder.build().toString())
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    override fun videoUrlRequest(episode: SEpisode): Request {
        return GET(episode.url)
    }

    companion object {
        const val PREF_KEY_VIDEO_QUALITY = "PREF_KEY_VIDEO_QUALITY"
        const val DEFAULT_QUALITY = "1080P"
    }
}
