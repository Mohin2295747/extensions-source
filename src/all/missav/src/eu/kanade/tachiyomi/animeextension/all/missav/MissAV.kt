package eu.kanade.tachiyomi.animeextension.all.missav

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.javcoverfetcher.JavCoverFetcher
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MissAV : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "MissAV"
    override val lang = "all"
    override val baseUrl = "https://missav.ai"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

    private val playlistExtractor by lazy {
        PlaylistUtils(client, headers)
    }

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/en/today-hot?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        return parseAnimeListing(document)
    }

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/en/new?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        return parseAnimeListing(document)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            val params = getSearchParameters(filters)
            
            // Get single genre filter
            val singleGenre = filters.getOrNull(0) as? SingleGenreFilter
            val selectedSingleGenre = if (singleGenre?.state ?: 0 > 0) {
                SingleGenreFilter.GENRES[singleGenre!!.state]
            } else {
                null
            }
            
            // Get first included genre from multi-filter for base search
            val firstIncludedGenre = params.include.firstOrNull()
            
            val path = when {
                query.isNotBlank() -> {
                    "en/search/${query.trim()}"
                }
                selectedSingleGenre != null && selectedSingleGenre.second.isNotEmpty() -> {
                    // Use single genre filter
                    selectedSingleGenre.second
                }
                firstIncludedGenre != null -> {
                    // Use first included genre from multi-filter
                    firstIncludedGenre.second
                }
                else -> {
                    "en/new"
                }
            }
            
            addEncodedPathSegments(path)
            addQueryParameter("page", page.toString())
            
            // Add sort if available
            val sortFilter = filters.getOrNull(1) as? SortFilter
            sortFilter?.state?.let {
                if (it > 0) {
                    addQueryParameter("sort", SortFilter.SORT_QUERIES[it])
                }
            }
        }.build().toString()

        return GET(url, headers)
    }

    override fun getFilterList() = getFilters()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        return parseAnimeListing(document)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val params = getSearchParameters(filters)
        
        // Check if we need multi-genre filtering
        val needsMultiFilter = params.include.size > 1 || params.exclude.isNotEmpty()
        
        if (!needsMultiFilter) {
            return super.getSearchAnime(page, query, filters)
        }
        
        return applyMultiGenreFilter(page, query, filters, params)
    }

    private suspend fun applyMultiGenreFilter(
        page: Int,
        query: String,
        filters: AnimeFilterList,
        params: FilterSearchParams,
    ): AnimesPage {
        val results = mutableListOf<SAnime>()
        var currentPage = page
        var hasNextPage = true
        val maxResults = preferences.getInt("multi_genre_limit", 20)
        var processed = 0
        
        while (results.size < maxResults && hasNextPage && processed < 60) {
            val pageResult = super.getSearchAnime(currentPage, query, filters)
            hasNextPage = pageResult.hasNextPage
            
            for (anime in pageResult.animes) {
                if (results.size >= maxResults) break
                
                delay(80)
                
                try {
                    val genres = GenreCache.get(anime.url) ?: run {
                        val detailsResponse = client.newCall(GET(anime.url, headers)).execute()
                        val details = if (detailsResponse.isSuccessful) {
                            animeDetailsParse(detailsResponse)
                        } else {
                            SAnime.create()
                        }
                        detailsResponse.close()
                        
                        val genreList = details.genre?.split(", ")?.map { it.trim() } ?: emptyList()
                        if (genreList.isNotEmpty()) {
                            GenreCache.put(anime.url, genreList)
                        }
                        genreList
                    }
                    
                    // Check all included genres are present
                    val includesAll = params.include.all { (display, _) ->
                        genres.any { genre -> 
                            genre.contains(display, ignoreCase = true)
                        }
                    }
                    
                    // Check no excluded genres are present
                    val excludesAll = params.exclude.none { (display, _) ->
                        genres.any { genre ->
                            genre.contains(display, ignoreCase = true)
                        }
                    }
                    
                    if (includesAll && excludesAll) {
                        results.add(anime)
                    }
                } catch (e: Exception) {
                    continue
                }
                
                processed++
                if (processed >= 60) break
            }
            
            currentPage++
            if (processed >= 60) break
        }
        
        return AnimesPage(results, hasNextPage && results.size >= 20)
    }

    private fun parseAnimeListing(document: org.jsoup.nodes.Document): AnimesPage {
        val entries = document.select("div.thumbnail, div.grid-item").mapNotNull { element ->
            val link = element.selectFirst("a.text-secondary, a.grid-item__link") ?: return@mapNotNull null
            val title = link.text().trim()
            val url = link.attr("href")
            val img = element.selectFirst("img")
            
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            
            SAnime.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = img?.attr("abs:data-src") ?: img?.attr("abs:src")
                
                element.selectFirst("div.duration, .video-duration")?.text()?.let { duration ->
                    description = "Duration: $duration"
                }
            }
        }
        
        val hasNextPage = document.selectFirst("a[rel=next], .pagination .next, .page-next") != null
        
        return AnimesPage(entries, hasNextPage)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        
        val jpTitle = document.select("div.text-secondary span:contains(title) + span").text()
        val siteCover = document.selectFirst("video.player")?.attr("abs:data-poster")
            ?: document.selectFirst("meta[property=og:image]")?.attr("abs:content")
        
        return SAnime.create().apply {
            title = document.selectFirst("h1.text-base, h1.title")?.text() ?: ""
            genre = document.select("a[href*=/genres/]").eachText().joinToString(", ")
            artist = document.select("a[href*=/actresses/]").eachText().joinToString(", ")
            author = document.select("a[href*=/directors/], a[href*=/makers/]").eachText().joinToString(", ")
            status = SAnime.COMPLETED
            
            description = buildString {
                document.selectFirst("div.mb-1, .description, .synopsis")?.text()?.takeIf { it.isNotBlank() }?.let {
                    append(it.trim())
                }
                
                val metadata = listOf(
                    "Label" to document.select("a[href*=/labels/]").eachText().joinToString(),
                    "Series" to document.select("a[href*=/series/]").eachText().joinToString(),
                    "Duration" to document.getInfo("Duration:"),
                    "Release Date" to document.getInfo("Date:"),
                    "Studio" to document.select("a[href*=/studios/]").eachText().joinToString(),
                )
                
                metadata.forEach { (label, value) ->
                    value?.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append("\n")
                        append("$label: $it")
                    }
                }
            }
            
            thumbnail_url = if (preferences.getBoolean("fetch_hd_covers", false) && jpTitle.isNotBlank()) {
                JavCoverFetcher.getCoverByTitle(jpTitle) ?: siteCover
            } else {
                siteCover
            }
        }
    }

    private fun Element.getInfo(label: String): String? {
        return select("div.text-secondary:contains($label)").firstOrNull()
            ?.text()
            ?.substringAfter("$label")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                url = anime.url
                name = "Episode"
                episode_number = 1F
            },
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException("Not used")
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        
        // Try to extract from packed JavaScript
        for (script in document.select("script")) {
            val scriptData = script.data()
            if (scriptData.contains("function(p,a,c,k,e,d)")) {
                try {
                    val unpacked = Unpacker.unpack(scriptData)
                    val masterPlaylist = unpacked.substringAfter("source=\"").substringBefore("\";")
                    if (masterPlaylist.isNotBlank()) {
                        return playlistExtractor.extractFromHls(masterPlaylist, referer = "$baseUrl/")
                    }
                } catch (e: Exception) {
                }
            }
        }
        
        // Try direct video sources
        document.select("video.player source[src]").forEach { source ->
            val url = source.attr("abs:src")
            if (url.isNotBlank() && url.contains("m3u8", ignoreCase = true)) {
                return playlistExtractor.extractFromHls(url, referer = "$baseUrl/")
            }
        }
        
        return emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720") ?: "720"
        
        return sortedWith(
            compareByDescending { video ->
                when {
                    quality == "auto" -> {
                        val qualityNum = video.quality.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                        qualityNum
                    }
                    video.quality.contains(quality, ignoreCase = true) -> 1
                    else -> 0
                }
            }.thenByDescending { video ->
                val qualityNum = video.quality.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                qualityNum
            },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("720p", "480p", "360p", "Auto (Highest)")
            entryValues = arrayOf("720", "480", "360", "auto")
            setDefaultValue("720")
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "multi_genre_limit"
            title = "Multi-genre search limit"
            entries = arrayOf("10 videos", "20 videos", "30 videos", "50 videos")
            entryValues = arrayOf("10", "20", "30", "50")
            setDefaultValue("20")
            summary = "%s"
        }.also(screen::addPreference)

        androidx.preference.Preference(screen.context).apply {
            title = "Clear genre cache"
            summary = "Clear cached genre data"
            setOnPreferenceClickListener {
                GenreCache.clear()
                true
            }
        }.also(screen::addPreference)

        JavCoverFetcher.addPreferenceToScreen(screen)
    }
}
