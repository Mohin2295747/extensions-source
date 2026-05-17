package eu.kanade.tachiyomi.animeextension.all.cosplaytele

import android.app.Application
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
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class CosplayTeleVideo : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "CosplayTele (Video)"

    override val baseUrl = "https://cosplaytele.com"

    override val lang = "all"

    override val supportsLatest = true

    private val popularPageLimit = 20

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/wp-json/wordpress-popular-posts/v1/popular-posts".toHttpUrl().newBuilder()
            .addQueryParameter("offset", (page * popularPageLimit).toString())
            .addQueryParameter("limit", popularPageLimit.toString())
            .addQueryParameter("range", "last7days")
            .addQueryParameter("embed", "true")
            .addQueryParameter("_embed", "wp:featuredmedia")
            .addQueryParameter("_fields", "title,link,_embedded,_links.wp:featuredmedia")
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val result = json.decodeFromString<List<PopularPostDto>>(response.body.string())
        val animes = result.map { item ->
            SAnime.create().apply {
                title = item.title.rendered
                setUrlWithoutDomain(item.link)
                thumbnail_url = item.embedded?.featuredMedia?.getOrNull(0)?.sourceUrl
                status = SAnime.UNKNOWN
                initialized = true
            }
        }
        return AnimesPage(animes, animes.size >= popularPageLimit)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var selectedCategory = ""
        for (filter in filters) {
            if (filter is CategoryFilter) {
                if (filter.state != 0) {
                    selectedCategory = CATEGORIES[filter.state].second
                }
                break
            }
        }

        val url = when {
            selectedCategory.isNotEmpty() -> {
                if (query.isNotEmpty()) {
                    "$baseUrl/$selectedCategory/page/$page/?s=$query"
                } else {
                    "$baseUrl/$selectedCategory/page/$page/"
                }
            }
            query.isNotEmpty() -> {
                "$baseUrl/page/$page/?s=$query"
            }
            else -> {
                "$baseUrl/page/$page/"
            }
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("main div.box").map { element ->
            SAnime.create().apply {
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                val linkEl = element.selectFirst("h5 a") ?: throw Exception("Title link missing")
                title = linkEl.text()
                setUrlWithoutDomain(linkEl.attr("abs:href"))
                status = SAnime.UNKNOWN
                initialized = true
            }
        }
        val hasNextPage = document.selectFirst(".next.page-number") != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val title = document.selectFirst(".entry-title")?.text() ?: throw Exception("No title found")
        return SAnime.create().apply {
            this.title = title
            description = title
            genre = getTags(document).joinToString(", ")
            status = SAnime.COMPLETED
            initialized = true
            setUrlWithoutDomain(response.request.url.toString())
        }
    }

    private fun getTags(document: Element): List<String> {
        val tags = mutableListOf<String>()
        document.select("#main a").forEach { a ->
            val href = a.attr("abs:href")
            if (TAG_PATTERN.matches(href)) {
                val tag = a.text()
                if (tag.isNotEmpty()) tags.add(tag)
            }
        }
        return tags
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()
        val dateStr = document.selectFirst("time.updated")?.attr("datetime")
        val dateUpload = if (dateStr != null) {
            try {
                DATE_FORMAT.parse(dateStr.substringBefore("T"))?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        } else {
            0L
        }
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1f
                this.url = url
                date_upload = dateUpload
            },
        )
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val postResponse = client.newCall(GET(episode.url, headers)).execute()
        if (!postResponse.isSuccessful) return emptyList()
        val postDocument = postResponse.asJsoup()
        postResponse.close()

        val iframe = postDocument.selectFirst("iframe[src*='cossora.stream/embed/']")
            ?: return emptyList()
        val embedUrl = iframe.attr("abs:src")
        if (embedUrl.isBlank()) return emptyList()

        val videoId = embedUrl.substringAfter("/embed/").substringBefore("/")
        if (videoId.isBlank()) return emptyList()

        val apiHeaders = headers.newBuilder()
            .add("Referer", embedUrl)
            .add("Origin", "https://cossora.stream")
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
            .add("Accept", "*/*")
            .build()

        val indexUrl = "https://cossora.stream/api-embed/$videoId/index.m3u8"
        val indexResponse = client.newCall(GET(indexUrl, apiHeaders)).execute()

        if (!indexResponse.isSuccessful) {
            indexResponse.close()
            return emptyList()
        }

        val indexContent = indexResponse.body?.string().orEmpty()
        indexResponse.close()

        val masterPattern = Regex("(master_\\d+p\\.m3u8\\?token=[a-zA-Z0-9._-]+\\.[a-zA-Z0-9._-]+\\.[a-zA-Z0-9._-]+=?)")
        val masterMatch = masterPattern.find(indexContent)

        if (masterMatch == null) return emptyList()

        val masterUrl = if (masterMatch.value.startsWith("http")) {
            masterMatch.value
        } else {
            "https://cossora.stream/api-embed/$videoId/${masterMatch.value}"
        }

        val quality = Regex("master_(\\d+p)").find(masterUrl)?.groupValues?.get(1) ?: "Unknown"

        val videoHeaders = headers.newBuilder()
            .add("Referer", embedUrl)
            .add("Origin", "https://cossora.stream")
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
            .build()

        return listOf(Video(masterUrl, quality, masterUrl, videoHeaders))
    }

    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Use getVideoList")
    }

    override fun videoListRequest(episode: SEpisode): Request {
        throw UnsupportedOperationException("Use getVideoList")
    }

    override fun getFilterList(): AnimeFilterList {
        val categoryEntries = CATEGORIES.map { it.first }.toTypedArray()
        val filters = mutableListOf<CategoryFilter>()
        filters.add(CategoryFilter("Category", categoryEntries))
        return AnimeFilterList(filters)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
    }

    companion object {
        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
        private val TAG_PATTERN = ".*/(tag|category)/.*".toRegex()
    }
}
