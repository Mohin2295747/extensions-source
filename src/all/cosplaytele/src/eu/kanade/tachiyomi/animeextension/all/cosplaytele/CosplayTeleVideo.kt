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
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

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
        val result = response.parseAs<List<PopularPostDto>>()
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
        val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
        val selectedCategory = if (categoryFilter != null && categoryFilter.state != 0) {
            CATEGORIES[categoryFilter.state].second
        } else ""

        val urlBuilder = when {
            selectedCategory.isNotEmpty() -> {
                baseUrl.toHttpUrl().newBuilder().apply {
                    addPathSegments(selectedCategory)
                    addPathSegment("page")
                    addPathSegment(page.toString())
                    if (query.isNotEmpty()) addQueryParameter("s", query)
                }
            }
            query.isNotEmpty() -> {
                "$baseUrl/page/$page/".toHttpUrl().newBuilder().addQueryParameter("s", query)
            }
            else -> latestUpdatesRequest(page).url.toHttpUrl().newBuilder()
        }
        return GET(urlBuilder.build(), headers)
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
        val dateUpload = DATE_FORMAT.tryParse(dateStr?.substringBefore("T")) ?: 0L
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1f
                url = url
                date_upload = dateUpload
            }
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

        val embedHeaders = headers.newBuilder()
            .add("Referer", baseUrl)
            .add("Origin", baseUrl)
            .build()
        val embedResponse = client.newCall(GET(embedUrl, embedHeaders)).execute()
        if (!embedResponse.isSuccessful) return emptyList()
        val embedHtml = embedResponse.body?.string().orEmpty()
        embedResponse.close()

        val videoList = extractM3u8Videos(embedHtml)
        return videoList.sortedByDescending { it.quality.toIntOrNull() ?: 0 }
    }

    private fun extractM3u8Videos(html: String): List<Video> {
        val pattern = Pattern.compile("(?:file|src)\\s*:\\s*\"([^\"]+\\.m3u8\\?[^\"]+)\"")
        val matcher = pattern.matcher(html)
        val urls = mutableSetOf<String>()
        while (matcher.find()) {
            urls.add(matcher.group(1))
        }

        if (urls.isEmpty()) return emptyList()

        return urls.map { url ->
            val quality = when {
                "master_1906p" in url -> "1906p"
                "master_1080p" in url -> "1080p"
                "master_720p" in url -> "720p"
                "master_480p" in url -> "480p"
                "master_360p" in url -> "360p"
                else -> {
                    val match = Regex("_(\\d+)p").find(url)
                    if (match != null) "${match.groupValues[1]}p" else "Unknown"
                }
            }
            Video(
                url,
                quality,
                url,
                headers.newBuilder()
                    .add("Referer", "https://cossora.stream/")
                    .add("Origin", "https://cossora.stream")
                    .build()
            )
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Use getVideoList")
    }

    override fun videoListRequest(episode: SEpisode): Request {
        throw UnsupportedOperationException("Use getVideoList")
    }

    override fun getFilterList(): AnimeFilterList {
        val categoryEntries = CATEGORIES.map { it.first }.toTypedArray()
        return AnimeFilterList(CategoryFilter("Category", categoryEntries))
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
    }

    companion object {
        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
        private val TAG_PATTERN = ".*/(tag|category)/.*".toRegex()
    }
}
