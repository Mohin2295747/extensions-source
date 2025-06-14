package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.AnimeFilter
import eu.kanade.tachiyomi.animesource.AnimeFilterList
import eu.kanade.tachiyomi.animesource.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Hanime1 : AnimeHttpSource(), ConfigurableAnimeSource {
    override val id: Long = 1234567890L
    override val baseUrl = "https://hanime1.me"
    override val lang = "zh"
    override val name = "Hanime1.me"
    override val supportsLatest = true

    override val client = network.client

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Context>().getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }
    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private val uploadDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
    }

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
                val resp = JSONArray(response.body?.string()).getJSONObject(0)
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
            jsoup.select("script[type=application/ld+json]").first()?.data()?.let {
                val info = json.decodeFromString<JsonObject>(it)
                val rawTitle = info["name"]!!.jsonPrimitive.content
                val rawDesc = info["description"]!!.jsonPrimitive.content
                title = translateText(rawTitle)
                description = translateText(rawDesc)
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
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)
    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())
    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)
    override fun popularAnimeRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("div.search-doujin-videos.hidden-xs")

        val list = nodes.map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.select("a[class=overlay]").attr("href"))
                thumbnail_url = it.select("img + img").attr("src")
                val rawTitle = it.select("div.card-mobile-title").text()
                title = translateText(rawTitle)
                author = it.select(".card-mobile-user").text()
            }
        }

        val nextPage = jsoup.select("li.page-item a.page-link[rel=next]")
        return AnimesPage(list, nextPage.isNotEmpty())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search?page=$page&query=$query"
        return GET(url)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
    
    companion object {
        const val PREF_KEY_VIDEO_QUALITY = "PREF_KEY_VIDEO_QUALITY"
        const val DEFAULT_QUALITY = "1080P"
    }
}
