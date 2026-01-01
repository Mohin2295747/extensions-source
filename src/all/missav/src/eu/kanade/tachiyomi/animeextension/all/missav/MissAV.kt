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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MissAV :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "MissAV"
    override val baseUrl = "https://missav.com"
    override val lang = "all"
    override val supportsLatest = true

    private val application: Application = Injekt.get()

    /** 🔑 REQUIRED for Option B */
    private var lastSearchFilters: AnimeFilterList? = null

    override fun getFilterList(): AnimeFilterList = getFilters()

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/en/new?page=$page"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.item").map { element ->
            SAnime.create().apply {
                title = element.selectFirst("a")?.attr("title") ?: ""
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
                url = element.selectFirst("a")?.attr("href") ?: ""
            }
        }
        val hasNextPage = document.selectFirst("a[rel=next]") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        lastSearchFilters = filters

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

    override fun searchAnimeParse(response: Response): AnimesPage {
        val page = popularAnimeParse(response)

        val selectedPaths: List<String> =
            lastSearchFilters
                ?.firstInstanceOrNull<MultiGenreFilter>()
                ?.selectedGenrePaths
                ?: return page

        if (selectedPaths.isEmpty()) return page

        val filtered = page.animes.filter { anime ->
            val genres = anime.genre ?: return@filter false
            selectedPaths.all { path ->
                genres.contains(
                    path.substringAfterLast("/"),
                    ignoreCase = true,
                )
            }
        }

        return AnimesPage(filtered, page.hasNextPage)
    }

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            description = document.selectFirst("div.description")?.text()
            genre = document.select("a[href*=\"/genres/\"]")
                .joinToString { it.text() }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> =
        listOf(
            SEpisode.create().apply {
                name = "Episode"
                episode_number = 1f
                url = response.request.url.encodedPath
            },
        )

    // ============================== Video ==============================

    override fun videoListParse(response: Response): List<Video> = emptyList()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "dummy"
            title = "MissAV"
            summary = "No settings available"
            screen.addPreference(this)
        }
    }
}
