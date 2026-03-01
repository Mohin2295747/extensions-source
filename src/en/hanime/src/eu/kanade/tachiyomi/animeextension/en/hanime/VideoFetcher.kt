package eu.kanade.tachiyomi.animeextension.en.hanime

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import kotlin.math.floor

object VideoFetcher {
    private const val TOKEN = "033afe4831c6415399baba9a25ef2c01"

    private fun generateSignature(time: Long): String {
        val base = "c1{$time}$TOKEN"
        val bytes = MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun fetchVideoListGuest(episode: SEpisode, client: OkHttpClient, headers: Headers): List<Video> {
        val videoId = episode.url.substringAfter("?id=")
        val time = floor(System.currentTimeMillis() / 1000.0).toLong()
        val signature = generateSignature(time)

        val guestClient = client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()

        val manifestHeaders = Headers.Builder()
            .add("authority", "cached.freeanimehentai.net")
            .add("accept", "application/json")
            .add("accept-language", "en-GB,en-US;q=0.9,en;q=0.8")
            .add("origin", "https://hanime.tv")
            .add("referer", "https://hanime.tv/")
            .add("sec-ch-ua", "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"")
            .add("sec-ch-ua-mobile", "?1")
            .add("sec-ch-ua-platform", "\"Android\"")
            .add("sec-fetch-dest", "empty")
            .add("sec-fetch-mode", "cors")
            .add("sec-fetch-site", "cross-site")
            .add("user-agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
            .add("x-csrf-token", TOKEN)
            .add("x-license", "")
            .add("x-session-token", "")
            .add("x-signature", signature)
            .add("x-signature-version", "web2")
            .add("x-time", time.toString())
            .add("x-user-license", "")
            .build()

        val request = Request.Builder()
            .url("https://cached.freeanimehentai.net/api/v8/guest/videos/$videoId/manifest")
            .headers(manifestHeaders)
            .get()
            .build()

        return try {
            val response = guestClient.newCall(request).execute()
            val responseString = response.body.string()

            if (responseString.startsWith("<") || responseString.contains("error") || responseString.contains("401")) {
                emptyList()
            } else {
                val videoModel = responseString.parseAs<VideoModel>()
                videoModel.videosManifest?.servers
                    ?.flatMap { server ->
                        server.streams
                            .filter { it.isGuestAllowed == true }
                            .map { stream ->
                                Video(stream.url, "${server.name ?: "Server"} - ${stream.height}p", stream.url)
                            }
                    }?.distinctBy { it.url } ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
