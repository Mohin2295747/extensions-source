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
import eu.kanade.tachiyomi.lib.javcoverfetcher.JavCoverFetcher.fetchHDCovers
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
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

        val entries = document.select("div.thumbnail").map { element ->
            SAnime.create().apply {
                element.select("a.text-secondary").also {
                    setUrlWithoutDomain(it.attr("href"))
                    title = it.text()
                }
                thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
            }
        }

        val hasNextPage = document.selectFirst("a[rel=next]") != null

        return AnimesPage(entries, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/en/new?page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            val genre = filters.firstInstanceOrNull<GenreList>()?.selected
            val sortFilter = filters.firstInstanceOrNull<SortFilter>()?.selected

            if (query.isNotEmpty()) {
                addEncodedPathSegments("en/search")
                addPathSegment(query.trim())
            } else if (genre != null && genre.isNotEmpty()) {
                addEncodedPathSegments(genre)
            } else {
                addEncodedPathSegments("en/new")
            }

            sortFilter?.let {
                addQueryParameter("sort", it)
            }
            addQueryParameter("page", page.toString())
        }.build().toString()

        return GET(url, headers)
    }

    override fun getFilterList() = getFilters()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val entries = document.select("div.thumbnail").map { element ->
            SAnime.create().apply {
                element.select("a.text-secondary").also {
                    setUrlWithoutDomain(it.attr("href"))
                    title = it.text()
                }
                thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
            }
        }

        val hasNextPage = document.selectFirst("a[rel=next]") != null

        return AnimesPage(entries, hasNextPage)
    }

    override suspend fun searchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        // Get the initial page results
        val pageResult = super.searchAnime(page, query, filters)
        // Get filter states
        val multiGenreFilter = filters.firstInstanceOrNull<MultiGenreFilter>()
        // Only apply client-side filtering if:
        // 1. We have multiple genres selected
        // 2. There's no text query (text search overrides genre filters)
        // 3. We have results to filter
        if (multiGenreFilter?.selectedGenres?.isNotEmpty() == true && query.isEmpty() && pageResult.animes.isNotEmpty()) {
            // Apply client-side filtering for multiple genres
            val filteredEntries = mutableListOf<SAnime>()
            for (anime in pageResult.animes) {
                try {
                    // Fetch anime details to get genres
                    val detailsResponse = client.newCall(GET(anime.url, headers)).execute()
                    val details = animeDetailsParse(detailsResponse)
                    detailsResponse.close()
                    // Get anime genres
                    val animeGenres = details.genre?.split(", ") ?: emptyList()
                    // Check if anime contains ALL selected genres
                    val matchesAllGenres = multiGenreFilter.selectedGenres.all { selectedGenre ->
                        animeGenres.any { it.equals(selectedGenre, ignoreCase = true) }
                    }
                    if (matchesAllGenres) {
                        filteredEntries.add(anime)
                    }
                } catch (e: Exception) {
                    // If we can't fetch details, include the anime anyway
                    filteredEntries.add(anime)
                }
            }
            return AnimesPage(filteredEntries, pageResult.hasNextPage)
        }
        return pageResult
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val jpTitle = document.select("div.text-secondary span:contains(title) + span").text()
        val siteCover = document.selectFirst("video.player")?.attr("abs:data-poster")

        return SAnime.create().apply {
            title = document.selectFirst("h1.text-base")!!.text()
            genre = document.getInfo("/genres/")
            author = listOfNotNull(
                document.getInfo("/directors/"),
                document.getInfo("/makers/"),
            ).joinToString()
            artist = document.getInfo("/actresses/")
            status = SAnime.COMPLETED
            description = buildString {
                document.selectFirst("div.mb-1")?.text()?.also { append("$it\n") }

                document.getInfo("/labels/")?.also { append("\nLabel: $it") }
                document.getInfo("/series/")?.also { append("\nSeries: $it") }

                document.select("div.text-secondary:not(:has(a)):has(span)")
                    .eachText()
                    .forEach { append("\n$it") }
            }
            thumbnail_url = if (preferences.fetchHDCovers) {
                JavCoverFetcher.getCoverByTitle(jpTitle) ?: siteCover
            } else {
                siteCover
            }
        }
    }

    private fun Element.getInfo(urlPart: String) =
        select("div.text-secondary > a[href*=$urlPart]")
            .eachText()
            .joinToString()
            .takeIf(String::isNotBlank)

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                url = anime.url
                name = "Episode"
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val playlists = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            ?.let(Unpacker::unpack)?.ifEmpty { null }
            ?: return emptyList()

        val masterPlaylist = playlists.substringAfter("source=\"").substringBefore("\";")

        return playlistExtractor.extractFromHls(masterPlaylist, referer = "$baseUrl/")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = PREF_QUALITY_TITLE
            entries = arrayOf("720p", "480p", "360p")
            entryValues = arrayOf("720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        JavCoverFetcher.addPreferenceToScreen(screen)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException()
    }

    private inline fun <reified T> List<*>.firstInstanceOrNull(): T? =
        filterIsInstance<T>().firstOrNull()

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720"
    }
}
