package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import tachiyomi.core.common.util.system.logcat
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

class GoogleTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {
    private val okHttpClient = OkHttpClient()

    private companion object {
        private const val CLIENT1 = "gtx"
        private const val CLIENT2 = "webapp"
    }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        pages.mapValues { (_, v) ->
            v.blocks.map { b ->
                b.translation = translateText(toLang.code, b.text)
            }
        }
    }

    private suspend fun translateText(lang: String, text: String): String {
        val url = getTranslateUrl(lang, text)
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).await()
        val bodyString = response.body?.string() ?: return ""

        return try {
            val jSONArray = JSONArray(bodyString).getJSONArray(0).getJSONArray(0)
            jSONArray.getString(0)
        } catch (e: Exception) {
            logcat { "Image Translation Error : $e" }
            ""
        }
    }

    private fun getTranslateUrl(lang: String, text: String): String {
        return try {
            val token = calculateToken(text)
            val encode = URLEncoder.encode(text, "utf-8")
            "https://translate.google.com/translate_a/single?" +
                "client=$CLIENT1&sl=auto&tl=$lang&dt=at&dt=bd&dt=ex&dt=ld&dt=md&dt=qca&dt=rw&dt=rm&dt=ss&dt=t&otf=1&ssel=0&tsel=0&kc=1&tk=$token&q=$encode"
        } catch (unused: UnsupportedEncodingException) {
            val token2 = calculateToken(text)
            "https://translate.google.com/translate_a/single?" +
                "client=$CLIENT2&sl=auto&tl=$lang&dt=at&dt=bd&dt=ex&dt=ld&dt=md&dt=qca&dt=rw&dt=rm&dt=ss&dt=t&otf=1&ssel=0&tsel=0&kc=1&tk=$token2&q=$text"
        }
    }

    private fun calculateToken(str: String): String {
        val list = mutableListOf<Int>()
        var i = 0
        while (i < str.length) {
            val charCode = str.codePointAt(i)
            when {
                charCode < 128 -> list.add(charCode)
                charCode < 2048 -> {
                    list.add((charCode shr 6) or 192)
                    list.add((charCode and 63) or 128)
                }
                charCode in 55296..57343 && i + 1 < str.length -> {
                    val nextChar = str.codePointAt(i + 1)
                    if (nextChar in 56320..57343) {
                        val codePoint = ((charCode and 1023) shl 10) + (nextChar and 1023) + 65536
                        list.add((codePoint shr 18) or 240)
                        list.add(((codePoint shr 12) and 63) or 128)
                        list.add(((codePoint shr 6) and 63) or 128)
                        list.add((codePoint and 63) or 128)
                        i++
                    }
                }
                else -> {
                    list.add((charCode shr 12) or 224)
                    list.add(((charCode shr 6) and 63) or 128)
                    list.add((charCode and 63) or 128)
                }
            }
            i++
        }

        var j: Long = 406644
        for (num in list) j = RL(j + num.toLong(), "+-a^+6")
        var rL = RL(j, "+-3^+b+-f") xor 3293161072L
        if (rL < 0) rL = (rL and 2147483647L) + 2147483648L
        val j2 = rL % 1000000L
        return "$j2.${406644L xor j2}"
    }

    private fun RL(j: Long, str: String): Long {
        var result = j
        var i = 0
        while (i < str.length - 2) {
            val shift = if (str[i + 2] in 'a'..'z') str[i + 2].code - 'W'.code else str[i + 2].digitToInt()
            val shiftValue = if (str[i + 1] == '+') result ushr shift else result shl shift
            result = if (str[i] == '+') (result + shiftValue) and 4294967295L else result xor shiftValue
            i += 3
        }
        return result
    }

    override fun close() {}
}
