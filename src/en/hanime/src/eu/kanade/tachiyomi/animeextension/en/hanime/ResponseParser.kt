package eu.kanade.tachiyomi.animeextension.en.hanime

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.Response

object ResponseParser {
    fun parseSearchJson(response: Response, source: Hanime): AnimesPage {
        val jsonLine = response.body.string().ifEmpty { return AnimesPage(emptyList(), false) }
        val jResponse = jsonLine.parseAs<HAnimeResponse>()
        val hasNextPage = jResponse.page < jResponse.nbPages - 1
        val array = jResponse.hits.parseAs<Array<HitsModel>>()
        val animeList = array.groupBy { TitleUtils.getTitle(it.name) }
            .map { (_, items) -> items.first() }
            .map { item ->
                SAnime.create().apply {
                    title = TitleUtils.getTitle(item.name)
                    thumbnail_url = item.coverUrl
                    author = item.brand
                    description = item.description?.replace(Regex("<[^>]*>"), "")
                    status = SAnime.UNKNOWN
                    genre = item.tags.joinToString { it }
                    url = "/videos/hentai/" + item.slug
                    initialized = true
                }
            }
        return AnimesPage(animeList, hasNextPage)
    }

    fun parseAnimeDetails(response: Response, source: Hanime): SAnime {
        val html = response.body.string()
        val nuxtJson = extractNuxtJson(html)
        
        return SAnime.create().apply {
            if (nuxtJson != null) {
                try {
                    val state = nuxtJson["state"]?.jsonObject ?: return@apply
                    val data = state["data"]?.jsonObject ?: return@apply
                    val video = data["video"]?.jsonObject ?: return@apply
                    val hentaiVideo = video["hentai_video"]?.jsonObject ?: return@apply
                    
                    title = hentaiVideo["name"]?.jsonPrimitive?.content ?: ""
                    thumbnail_url = hentaiVideo["poster_url"]?.jsonPrimitive?.content
                    author = video["brand"]?.jsonObject?.get("title")?.jsonPrimitive?.content
                    description = hentaiVideo["description"]?.jsonPrimitive?.content?.replace(Regex("<[^>]*>"), "")
                    status = SAnime.UNKNOWN
                    
                    val tags = video["hentai_tags"]?.jsonArray
                        ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                        ?.joinToString { it }
                    genre = tags
                    
                    url = "/videos/hentai/" + (hentaiVideo["slug"]?.jsonPrimitive?.content ?: "")
                } catch (e: Exception) {
                }
            }
            initialized = true
        }
    }

    private fun extractNuxtJson(html: String): JsonObject? {
        val pattern = """window\.__NUXT__\s*=\s*(\{.*?\});""".toRegex(RegexOption.DOT_MATCHES_ALL)
        return pattern.find(html)?.groupValues?.get(1)?.let { jsonStr ->
            try {
                Json.parseToJsonElement(jsonStr).jsonObject
            } catch (e: Exception) {
                null
            }
        }
    }

    fun parseEpisodeList(response: Response, baseUrl: String): List<SEpisode> {
        val responseString = response.body.string()
        if (responseString.isEmpty()) return emptyList()
        
        return try {
            responseString.parseAs<VideoModel>().hentaiFranchiseHentaiVideos
                ?.mapIndexed { idx, it ->
                    SEpisode.create().apply {
                        episode_number = idx + 1f
                        name = "Episode ${idx + 1}"
                        date_upload = (it.releasedAtUnix ?: 0) * 1000
                        url = "$baseUrl/api/v8/video?id=${it.id}"
                    }
                }?.reversed() ?: emptyList()
        } catch (e: Exception) {
            val html = responseString
            val nuxtJson = extractNuxtJson(html) ?: return emptyList()
            
            try {
                val state = nuxtJson["state"]?.jsonObject ?: return emptyList()
                val data = state["data"]?.jsonObject ?: return emptyList()
                val video = data["video"]?.jsonObject ?: return emptyList()
                val franchiseVideos = video["hentai_franchise_hentai_videos"]?.jsonArray ?: return emptyList()
                
                franchiseVideos.mapIndexed { idx, jsonElement ->
                    val episodeJson = jsonElement.jsonObject
                    SEpisode.create().apply {
                        episode_number = idx + 1f
                        name = "Episode ${idx + 1}"
                        date_upload = (episodeJson["released_at_unix"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0) * 1000
                        url = "$baseUrl/api/v8/video?id=${episodeJson["id"]?.jsonPrimitive?.content}"
                    }
                }.reversed()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
