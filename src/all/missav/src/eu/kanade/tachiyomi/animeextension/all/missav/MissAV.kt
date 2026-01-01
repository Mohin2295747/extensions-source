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

    // ===================== POPULAR / LATEST =====================

    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/en/today-hot?page=$page", headers)

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/en/new?page=$page", headers)

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

    override fun latestUpdatesParse(response: Response) =
        popularAnimeParse(response)

    // ===================== SEARCH =====================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            val genre = filters.firstInstanceOrNull<GenreList>()?.selected

            when {
                query.isNotBlank() -> {
                    addEncodedPathSegments("en/search")
                    addPathSegment(query.trim())
                }
                !genre.isNullOrBlank() -> addEncodedPathSegments(genre)
                else -> addEncodedPathSegments("en/new")
            }

            filters.firstInstanceOrNull<SortFilter>()?.selected?.let {
                addQueryParameter("sort", it)
            }

            addQueryParameter("page", page.toString())
        }.build().toString()

        return GET(url, headers)
    }

    override fun getFilterList() = getFilters()

    /**
     * OPTION B:
     * client-side multi-genre filtering
     */
    override fun searchAnimeParse(response: Response): AnimesPage {
        val page = popularAnimeParse(response)

        val selectedPaths =
            currentFilters
                ?.firstInstanceOrNull<MultiGenreFilter>()
                ?.selectedGenrePaths
                ?: return page

        if (selectedPaths.isEmpty()) return page

        val filtered = page.animes.filter { anime ->
            selectedPaths.all { path ->
                anime.genre?.contains(
                    path.substringAfterLast("/"),
                    ignoreCase = true,
                ) == true
            }
        }

        return AnimesPage(filtered, page.hasNextPage)
    }

    // ===================== DETAILS =====================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val jpTitle =
            document.select("div.text-secondary span:contains(title) + span").text()
        val siteCover =
            document.selectFirst("video.player")?.attr("abs:data-poster")

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
                document.selectFirst("div.mb-1")?.text()?.also {
                    append("$it\n")
                }
                document.getInfo("/labels/")?.also { append("\nLabel: $it") }
                document.getInfo("/series/")?.also { append("\nSeries: $it") }
            }

            thumbnail_url =
                if (preferences.fetchHDCovers) {
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

    // ===================== EPISODES / VIDEO =====================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        listOf(
            SEpisode.create().apply {
                url = anime.url
                name = "Episode"
            },
        )

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val packed = document
            .selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            ?.let(Unpacker::unpack)
            ?: return emptyList()

        val masterUrl =
            packed.substringAfter("source=\"").substringBefore("\";")

        return playlistExtractor.extractFromHls(
            masterUrl,
            referer = "$baseUrl/",
        )
    }

    override fun List<Video>.sort(): List<Video> {
        val quality =
            preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareBy { it.quality.contains(quality) }).reversed()
    }

    // ===================== PREFS =====================

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

    override fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException()

    private inline fun <reified T> List<*>.firstInstanceOrNull(): T? =
        filterIsInstance<T>().firstOrNull()

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720"
    }
}
