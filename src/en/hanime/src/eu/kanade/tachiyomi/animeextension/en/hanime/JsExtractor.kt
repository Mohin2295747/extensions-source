package eu.kanade.tachiyomi.animeextension.en.hanime

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

object JsExtractor {
    private val CLIENT = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun extractAuthTokens(pageUrl: String): Pair<String, Long> {
        return try {
            val request = Request.Builder()
                .url(pageUrl)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                )
                .build()

            val response = CLIENT.newCall(request).execute()
            val html = response.body.string()

            val videoId = extractVideoId(html) ?: ""
            HtmlAuthExtractor.extractAuthTokens(html, videoId)
        } catch (e: Exception) {
            Pair("", 0L)
        }
    }

    private fun extractVideoId(html: String): String? {
        val patterns = listOf(
            Pattern.compile("/api/v8/video\\?id=([^\"'&\\s]+)"),
            Pattern.compile("video_id[:\"']\\s*[\"']([^\"']+)[\"']"),
            Pattern.compile("data-video-id=[\"']([^\"']+)[\"']"),
        )

        patterns.forEach { pattern ->
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }
}
