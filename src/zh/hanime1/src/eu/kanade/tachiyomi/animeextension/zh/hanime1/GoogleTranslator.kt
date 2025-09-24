package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.translation.model.PageTranslation
import eu.kanade.tachiyomi.translation.translator.TextRecognizerLanguage
import eu.kanade.tachiyomi.translation.translator.TextTranslator
import eu.kanade.tachiyomi.translation.translator.TextTranslatorLanguage
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

    private val client = "gtx"
    private val okHttpClient = OkHttpClient()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        pages.values.forEach { pageTranslation ->
            pageTranslation.blocks.forEach { block ->
                block.translation = translateText(block.text)
            }
        }
    }

    private suspend fun translateText(text: String): String {
        val url = buildTranslateUrl(text)
        val request = Request.Builder().url(url).build()
        
        return try {
            val response = okHttpClient.newCall(request).await()
            val body = response.body?.string() ?: ""
            parseTranslationResponse(body)
        } catch (e: Exception) {
            logcat { "Translation error: $e" }
            ""
        }
    }

    private fun buildTranslateUrl(text: String): String {
        val encodedText = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: UnsupportedEncodingException) {
            text
        }
        
        val token = generateToken(text)
        return "https://translate.google.com/translate_a/single?" +
            "client=$client&sl=auto&tl=${toLang.code}&dt=t&tk=$token&q=$encodedText"
    }

    private fun generateToken(text: String): String {
        var hash: Long = 406644
        
        text.forEach { char ->
            val code = char.code
            hash = rotateLeft(hash + code, "+-a^+6")
        }
        
        hash = rotateLeft(hash, "+-3^+b+-f") xor 3293161072L
        if (hash < 0) hash = (hash and 2147483647L) + 2147483648L
        
        val result = hash % 1000000L
        return "$result.${406644L xor result}"
    }

    private fun rotateLeft(value: Long, operation: String): Long {
        var result = value
        var i = 0
        
        while (i < operation.length - 2) {
            val operator = operation[i]
            val direction = operation[i + 1]
            val shiftChar = operation[i + 2]
            
            val shift = if (shiftChar in 'a'..'z') shiftChar.code - 'W'.code else shiftChar.digitToInt()
            val shiftValue = if (direction == '+') result ushr shift else result shl shift
            
            result = if (operator == '+') (result + shiftValue) and 0xFFFFFFFF else result xor shiftValue
            i += 3
        }
        
        return result
    }

    private fun parseTranslationResponse(response: String): String {
        return try {
            JSONArray(response)
                .getJSONArray(0)
                .getJSONArray(0)
                .getString(0)
        } catch (e: Exception) {
            logcat { "Failed to parse translation response: $e" }
            ""
        }
    }

    override fun close() {}
}            result = if (str[i] == '+') (result + shiftVal) and 4294967295L else result xor shiftVal
            i += 3
        }
        return result
    }

    override fun close() {}
}
