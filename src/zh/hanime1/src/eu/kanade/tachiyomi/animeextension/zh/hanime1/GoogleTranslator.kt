package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.translation.model.PageTranslation
import eu.kanade.tachiyomi.translation.translator.TextRecognizerLanguage
import eu.kanade.tachiyomi.translation.translator.TextTranslator
import eu.kanade.tachiyomi.translation.translator.TextTranslatorLanguage
import eu.kanade.tachiyomi.network.await
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

    private val client1 = "gtx"
    private val client2 = "webapp"
    private val okHttpClient = OkHttpClient()

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
        val bodyString = response.body?.string() ?: ""
        return try {
            val jsonArray = JSONArray(bodyString).getJSONArray(0).getJSONArray(0)
            jsonArray.getString(0)
        } catch (e: Exception) {
            logcat { "Image Translation Error: $e" }
            ""
        }
    }

    private fun getTranslateUrl(lang: String, text: String): String {
        return try {
            val token = calculateToken(text)
            val encodedText = URLEncoder.encode(text, "utf-8")
            "https://translate.google.com/translate_a/single?client=$client1&sl=auto&tl=$lang&dt=at&dt=bd&dt=ex&dt=ld&dt=md&dt=qca&dt=rw&dt=rm&dt=ss&dt=t&otf=1&ssel=0&tsel=0&kc=1&tk=$token&q=$encodedText"
        } catch (e: UnsupportedEncodingException) {
            val token = calculateToken(text)
            "https://translate.google.com/translate_a/single?client=$client2&sl=auto&tl=$lang&dt=at&dt=bd&dt=ex&dt=ld&dt=md&dt=qca&dt=rw&dt=rm&dt=ss&dt=t&otf=1&ssel=0&tsel=0&kc=1&tk=$token&q=$text"
        }
    }

    private fun calculateToken(str: String): String {
        val list = mutableListOf<Int>()
        var i = 0
        while (i < str.length) {
            val code = str.codePointAt(i)
            when {
                code < 128 -> list.add(code)
                code < 2048 -> {
                    list.add((code shr 6) or 192)
                    list.add((code and 63) or 128)
                }
                code in 55296..57343 && i + 1 < str.length -> {
                    val nextCode = str.codePointAt(i + 1)
                    if (nextCode in 56320..57343) {
                        val cp = ((code and 1023) shl 10) + (nextCode and 1023) + 65536
                        list.add((cp shr 18) or 240)
                        list.add(((cp shr 12) and 63) or 128)
                        list.add(((cp shr 6) and 63) or 128)
                        list.add((cp and 63) or 128)
                        i++
                    }
                }
                else -> {
                    list.add((code shr 12) or 224)
                    list.add(((code shr 6) and 63) or 128)
                    list.add((code and 63) or 128)
                }
            }
            i++
        }
        var j: Long = 406644
        for (num in list) {
            j = rl(j + num.toLong(), "+-a^+6")
        }
        var rL = rl(j, "+-3^+b+-f") xor 3293161072L
        if (rL < 0) rL = (rL and 2147483647L) + 2147483648L
        val j2 = rL % 1000000L
        return "$j2.${406644L xor j2}"
    }

    private fun rl(value: Long, str: String): Long {
        var result = value
        var i = 0
        while (i < str.length - 2) {
            val shift = if (str[i + 2] in 'a'..'z') str[i + 2].code - 'W'.code else str[i + 2].digitToInt()
            val shiftVal = if (str[i + 1] == '+') result ushr shift else result shl shift
            result = if (str[i] == '+') (result + shiftVal) and 4294967295L else result xor shiftVal
            i += 3
        }
        return result
    }

    override fun close() {}
}
