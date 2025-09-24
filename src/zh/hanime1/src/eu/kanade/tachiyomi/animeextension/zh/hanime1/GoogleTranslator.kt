package eu.kanade.tachiyomi.animeextension.zh.hanime1

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

object GoogleTranslator {

    private val client = OkHttpClient()

    /**
     * Translate a single string or multiple joined by "|||"
     */
    fun translate(text: String, targetLang: String = "en"): String {
        return try {
            if (text.isBlank()) return text

            val url =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=" +
                    URLEncoder.encode(text, "UTF-8")

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return text
                val arr = JSONObject("{\"data\":$body}").getJSONArray("data").getJSONArray(0)

                // Google Translate free endpoint returns [[["translated","original",...],...],...]
                val result = StringBuilder()
                for (i in 0 until arr.length()) {
                    result.append(arr.getJSONArray(i).getString(0))
                }
                result.toString()
            }
        } catch (e: Exception) {
            text // fallback to original
        }
    }

    /**
     * Translate an array with batching using "|||"
     */
    fun translateList(list: Array<String>, targetLang: String = "en"): Array<String> {
        if (list.isEmpty()) return list
        val joined = list.joinToString("|||")
        val translated = translate(joined, targetLang)
        return translated.split("|||").map { it.trim() }.toTypedArray()
    }
}
