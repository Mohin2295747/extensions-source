package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.json.JSONArray as JsonArrayJ
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Translator helper for the Hanime1 source.
 *
 * NOTE: This implementation performs blocking HTTP calls (okHttp execute) and exposes
 * synchronous functions so it can be used from non-suspending callers in the extension
 * (Tachiyomi parsing calls which are not suspend). If you prefer suspend-based code,
 * convert callers to suspend and change these methods accordingly.
 */
class Hanime1Translator {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<android.app.Application>()
            .getSharedPreferences("source_${Hanime1().id}", 0)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        const val PREF_KEY_TRANSLATION_ENABLED = "pref_translation_enabled"
        const val PREF_KEY_TARGET_LANGUAGE = "pref_target_language"
        const val DEFAULT_TARGET_LANGUAGE = "en"
    }

    fun isTranslationEnabled(): Boolean =
        preferences.getBoolean(PREF_KEY_TRANSLATION_ENABLED, false)

    fun getTargetLanguage(): String =
        preferences.getString(PREF_KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE)
            ?: DEFAULT_TARGET_LANGUAGE

    /**
     * Translate important fields on the provided SAnime.
     * This function is blocking (intended for synchronous callers).
     */
    fun translateAnimeDetails(anime: SAnime): SAnime {
        if (!isTranslationEnabled()) return anime

        val translated = SAnime.create().apply {
            url = anime.url
            thumbnail_url = anime.thumbnail_url
            status = anime.status
            initialized = anime.initialized
        }

        try {
            if (!anime.title.isNullOrBlank()) {
                val t = translateText(getTargetLanguage(), anime.title!!)
                translated.title = if (t.isNotBlank()) t else anime.title
            } else {
                translated.title = anime.title
            }

            if (!anime.description.isNullOrBlank()) {
                val t = translateText(getTargetLanguage(), anime.description!!)
                translated.description = if (t.isNotBlank()) t else anime.description
            } else {
                translated.description = anime.description
            }

            anime.author?.let { author ->
                if (author.isNotBlank()) {
                    val t = translateText(getTargetLanguage(), author)
                    translated.author = if (t.isNotBlank()) t else author
                } else {
                    translated.author = author
                }
            }

            anime.genre?.let { genre ->
                if (genre.isNotBlank()) {
                    val t = translateText(getTargetLanguage(), genre)
                    translated.genre = if (t.isNotBlank()) t else genre
                } else {
                    translated.genre = genre
                }
            }
        } catch (ignored: Exception) {
            // If anything fails, return original anime without crashing extension.
            return anime
        }

        return translated
    }

    /**
     * Convenience overload: translate a single text using default target language.
     * Blocking.
     */
    fun translateText(text: String): String =
        if (text.isBlank()) text else translateText(getTargetLanguage(), text)

    /**
     * Blocking translation: splits long text into chunks and queries translate endpoint.
     *
     * Note: This implementation uses google translate 'translate_a/single' response
     * parsing similar to many lightweight translators. The token generation is a ported
     * approximation (no external dependency).
     */
    fun translateText(targetLang: String, text: String): String {
        if (text.isBlank()) return text

        val chunks = splitTextIntoChunks(text)
        val translatedChunks = mutableListOf<String>()

        for (chunk in chunks) {
            if (chunk.isBlank()) continue
            try {
                val url = buildTranslateUrl(targetLang, chunk)
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    )
                    .build()

                val response = okHttpClient.newCall(request).execute()
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        try {
                            val jsonArray = JSONArray(body)
                            val translationArray = jsonArray.optJSONArray(0)
                            val builder = StringBuilder()
                            if (translationArray != null) {
                                for (i in 0 until translationArray.length()) {
                                    val sentence = translationArray.optJSONArray(i)
                                    if (sentence != null && sentence.length() > 0) {
                                        builder.append(sentence.optString(0))
                                    }
                                }
                            }
                            if (builder.isNotEmpty()) {
                                translatedChunks.add(builder.toString())
                                continue
                            }
                        } catch (_: Exception) {
                            // Fall through to fallback that returns original chunk
                        }
                    }
                }
            } catch (_: Exception) {
                // network or parsing error -> fallback to raw chunk
            }
            translatedChunks.add(chunk)
        }
        // Join preserving original separation - use empty string to match original behaviour
        return translatedChunks.joinToString(separator = "")
    }

    private fun splitTextIntoChunks(text: String, maxChunkLength: Int = 1500): List<String> {
        val chunks = mutableListOf<String>()
        val sentences = text.split('。', '！', '!', '？', '?', '\n').filter { it.isNotBlank() }

        if (sentences.size > 1) {
            var current = StringBuilder()
            for (sentence in sentences) {
                val s = when {
                    text.contains(sentence + '。') -> sentence + "。"
                    text.contains(sentence + '！') -> sentence + "！"
                    text.contains(sentence + '!') -> sentence + "!"
                    text.contains(sentence + '？') -> sentence + "？"
                    text.contains(sentence + '?') -> sentence + "?"
                    else -> sentence
                }

                if (current.length + s.length <= maxChunkLength) {
                    current.append(s)
                } else {
                    if (current.isNotEmpty()) {
                        chunks.add(current.toString())
                        current = StringBuilder()
                    }
                    if (s.length > maxChunkLength) {
                        var t = s
                        while (t.length > maxChunkLength) {
                            chunks.add(t.substring(0, maxChunkLength))
                            t = t.substring(maxChunkLength)
                        }
                        if (t.isNotEmpty()) {
                            current.append(t)
                        }
                    } else {
                        current.append(s)
                    }
                }
            }
            if (current.isNotEmpty()) {
                chunks.add(current.toString())
            }
        } else {
            var remain = text
            while (remain.length > maxChunkLength) {
                chunks.add(remain.substring(0, maxChunkLength))
                remain = remain.substring(maxChunkLength)
            }
            if (remain.isNotEmpty()) chunks.add(remain)
        }

        return chunks
    }

    private fun buildTranslateUrl(targetLang: String, text: String): String {
        val client = "gtx"
        val token = calculateToken(text)
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            "https://translate.google.com/translate_a/single?client=$client&sl=auto&tl=$targetLang&dt=t&tk=$token&q=$encoded"
        } catch (_: UnsupportedEncodingException) {
            "https://translate.google.com/translate_a/single?client=$client&sl=auto&tl=$targetLang&dt=t&tk=$token&q=$text"
        }
    }

    /**
     * Token calculation adapted from common JS ports. It's an approximation used by many
     * lightweight translators; behaviour may change if Google modifies token scheme.
     */
    private fun calculateToken(text: String): String {
        val list = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                cp < 128 -> list.add(cp)
                cp < 2048 -> {
                    list.add((cp shr 6) or 192)
                    list.add((cp and 63) or 128)
                }
                cp in 0xD800..0xDBFF && i + 1 < text.length -> {
                    // handle surrogate pairs
                    val next = text.codePointAt(i + 1)
                    if (next in 0xDC00..0xDFFF) {
                        val combined = ((cp and 1023) shl 10) + (next and 1023) + 65536
                        list.add((combined shr 18) or 240)
                        list.add(((combined shr 12) and 63) or 128)
                        list.add(((combined shr 6) and 63) or 128)
                        list.add((combined and 63) or 128)
                        i++
                    } else {
                        list.add((cp shr 12) or 224)
                        list.add(((cp shr 6) and 63) or 128)
                        list.add((cp and 63) or 128)
                    }
                }
                else -> {
                    list.add((cp shr 12) or 224)
                    list.add(((cp shr 6) and 63) or 128)
                    list.add((cp and 63) or 128)
                }
            }
            i++
        }

        var j: Long = 406644L
        for (num in list) {
            j = rl(j + num.toLong(), "+-a^+6")
        }
        var result = rl(j, "+-3^+b+-f") xor 3293161072L
        if (result < 0) {
            result = (result and 2147483647L) + 2147483648L
        }
        val mod = result % 1_000_000L
        return "$mod.${406644L xor mod}"
    }

    private fun rl(value: Long, op: String): Long {
        var r = value
        var i = 0
        while (i < op.length - 2) {
            val c2 = op[i + 2]
            val d = if (c2 in 'a'..'z') {
                c2.code - 'a'.code + 10
            } else {
                c2.digitToInt()
            }
            val shift = if (op[i + 1] == '+') {
                r ushr d
            } else {
                r shl d
            }
            r = if (op[i] == '+') {
                (r + shift) and 0xFFFFFFFFL
            } else {
                r xor shift
            }
            i += 3
        }
        return r
    }

    suspend fun translateFilterValues(values: List<String>): List<String> {
        if (!isTranslationEnabled()) return values
        return values.map { v ->
            if (isChineseText(v)) translateText(v).ifEmpty { v } else v
        }
    }

    suspend fun translateFilterName(name: String): String {
        if (!isTranslationEnabled()) return name
        return if (isChineseText(name)) translateText(name).ifEmpty { name } else name
    }

    private val filterTermTranslations = mapOf(
        "全部" to "All",
        "裏番" to "R-18",
        "泡面番" to "Short Anime",
        "泡麵番" to "Short Anime",
        "Motion Anime" to "Motion Anime",
        "最新上市" to "Latest Release",
        "最新上傳" to "Latest Upload",
        "本日排行" to "Daily Ranking",
        "本週排行" to "Weekly Ranking",
        "本月排行" to "Monthly Ranking",
        "全部年份" to "All Years",
        "全部月份" to "All Months",
        "廣泛配對" to "Broad Match",
        "標籤" to "Tags",
        "影片類型" to "Video Type",
        "排序方式" to "Sort By",
        "發佈年份" to "Release Year",
        "發佈月份" to "Release Month",
        "發佈日期" to "Release Date",
    )

    suspend fun fastTranslateFilterText(text: String): String {
        if (!isTranslationEnabled()) return text
        return filterTermTranslations[text] ?: if (isChineseText(text)) translateText(text).ifEmpty { text } else text
    }

    private fun isChineseText(text: String): Boolean {
        if (text.isBlank()) return false
        val chineseCharCount = text.count { ch ->
            ch in '\u4e00'..'\u9fff' ||
                ch in '\u3400'..'\u4dbf' ||
                ch in '\uF900'..'\uFAFF' ||
                ch in '\u3000'..'\u303f' ||
                ch in '\uff00'..'\uffef'
        }
        return chineseCharCount > text.length * 0.3
    }
}

/**
 * Add translation preferences to the given PreferenceScreen.
 * Uses the same SharedPreferences file as the translator class (source_<id>).
 */
fun PreferenceScreen.addTranslationPreferences() {
    val sharedPrefs = Injekt.get<android.app.Application>()
        .getSharedPreferences("source_${Hanime1().id}", 0)

    addPreference(
        SwitchPreferenceCompat(context).apply {
            key = Hanime1Translator.PREF_KEY_TRANSLATION_ENABLED
            title = "Enable Translation"
            summary = "Translate Chinese text to chosen language"
            setDefaultValue(false)
        },
    )

    addPreference(
        ListPreference(context).apply {
            key = Hanime1Translator.PREF_KEY_TARGET_LANGUAGE
            title = "Target Language"
            entries = arrayOf("English", "繁體中文", "簡體中文", "日本語", "한국어")
            entryValues = arrayOf("en", "zh-TW", "zh-CN", "ja", "ko")
            setDefaultValue(Hanime1Translator.DEFAULT_TARGET_LANGUAGE)
            summary = "Current: ${
                sharedPrefs.getString(
                    Hanime1Translator.PREF_KEY_TARGET_LANGUAGE,
                    Hanime1Translator.DEFAULT_TARGET_LANGUAGE,
                )?.let { lang ->
                    when (lang) {
                        "en" -> "English"
                        "zh-TW" -> "繁體中文"
                        "zh-CN" -> "簡體中文"
                        "ja" -> "日本語"
                        "ko" -> "한국어"
                        else -> "English"
                    }
                }
            }"
            setOnPreferenceChangeListener { _, newValue ->
                summary = "Current: ${when (newValue as String) {
                    "en" -> "English"
                    "zh-TW" -> "繁體中文"
                    "zh-CN" -> "簡體中文"
                    "ja" -> "日本語"
                    "ko" -> "한국어"
                    else -> "English"
                }}"
                true
            }
        },
    )
}
