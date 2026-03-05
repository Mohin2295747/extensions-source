package eu.kanade.tachiyomi.animeextension.en.hanime

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object HtmlAuthExtractor {

    fun extractAuthTokens(html: String, videoId: String): Pair<String, Long> {
        val timestamp = extractTimestampFromNuxt(html)
        val signature = generateSignature(timestamp, videoId)
        return Pair(signature, timestamp)
    }

    private fun extractTimestampFromNuxt(html: String): Long {
        val pattern = """window\.__NUXT__\s*=\s*(\{.*?\});""".toRegex(RegexOption.DOT_MATCHES_ALL)

        return pattern.find(html)?.groupValues?.get(1)?.let { jsonStr ->
            try {
                val json = Json.parseToJsonElement(jsonStr).jsonObject
                json["state"]?.jsonObject?.let { state ->
                    state["data"]?.jsonObject?.let { data ->
                        data["video"]?.jsonObject?.let { video ->
                            video["stime"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        } ?: 0L
                    }
                } ?: 0L
            } catch (e: Exception) {
                0L
            }
        } ?: 0L
    }

    private fun generateSignature(timestamp: Long, videoId: String): String {
        val data = "$timestamp:guest:$videoId"
        return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
    }
}
