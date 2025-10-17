package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.SAnime
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Hanime1Translator {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<android.app.Application>()
            .getSharedPreferences("source_${Hanime1().id}", 0x0000)
    }

    private val okHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    companion object {
        const val PREF_KEY_TRANSLATION_ENABLED = "pref_translation_enabled"
        const val PREF_KEY_TARGET_LANGUAGE = "pref_target_language"
        const val DEFAULT_TARGET_LANGUAGE = "en"
    }

    fun isTranslationEnabled(): Boolean {
        return preferences.getBoolean(PREF_KEY_TRANSLATION_ENABLED, false)
    }

    fun getTargetLanguage(): String {
        return preferences.getString(PREF_KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE)
            ?: DEFAULT_TARGET_LANGUAGE
    }

    suspend fun translateAnimeDetails(anime: SAnime): SAnime {
        if (!isTranslationEnabled()) return anime

        val translatedAnime =
            SAnime.create().apply {
                url = anime.url
                thumbnail_url = anime.thumbnail_url
            }

        runBlocking {
            try {
                if (anime.title.isNotEmpty()) {
                    val t = translateText(getTargetLanguage(), anime.title)
                    translatedAnime.title = if (t.isNotEmpty()) t else anime.title
                }

                if (anime.description.isNotEmpty()) {
                    val t = translateText(getTargetLanguage(), anime.description)
                    translatedAnime.description = if (t.isNotEmpty()) t else anime.description
                }

                anime.author?.let { author ->
                    val t = translateText(getTargetLanguage(), author)
                    translatedAnime.author = if (t.isNotEmpty()) t else author
                }

                anime.genre?.let { genre ->
                    val t = translateText(getTargetLanguage(), genre)
                    translatedAnime.genre = if (t.isNotEmpty()) t else genre
                }

                translatedAnime.status = anime.status
                translatedAnime.initialized = anime.initialized
            } catch (_: Exception) {
                return@runBlocking anime
            }
        }
        return translatedAnime
    }

    suspend fun translateText(text: String): String {
        if (text.isBlank()) return text
        return translateText(getTargetLanguage(), text)
    }

    private suspend fun translateText(targetLang: String, text: String): String {
        if (text.isBlank()) return text
        val chunks = splitTextIntoChunks(text)
        val translatedChunks = mutableListOf<String>()

        for (chunk in chunks) {
            if (chunk.isBlank()) continue
            try {
                val url = buildTranslateUrl(targetLang, chunk)
                val request =
                    Request.Builder()
                        .url(url)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        )
                        .build()
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    try {
                        val jsonArray = JSONArray(body)
                        val translationArray = jsonArray.getJSONArray(0)
                        val builder = StringBuilder()
                        for (i in 0 until translationArray.length()) {
                            val sentence = translationArray.getJSONArray(i)
                            if (sentence.length() > 0) builder.append(sentence.getString(0))
                        }
                        if (builder.isNotEmpty()) {
                            translatedChunks.add(builder.toString())
                            continue
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            translatedChunks.add(chunk)
        }
        return translatedChunks.joinToString("")
    }

    private fun splitTextIntoChunks(text: String, maxChunkLength: Int = 1500): List<String> {
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        val sentences = text.split('。', '！', '!', '？', '?', '\n').filter { it.isNotBlank() }

        if (sentences.size > 1) {
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
                        if (t.isNotEmpty()) current.append(t)
                    } else {
                        current.append(s)
                    }
                }
            }
            if (current.isNotEmpty()) chunks.add(current.toString())
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
                cp in 55296..57343 && i + 1 < text.length -> {
                    val next = text.codePointAt(i + 1)
                    if (next in 56320..57343) {
                        val combined = ((cp and 1023) shl 10) + (next and 1023) + 65536
                        list.add((combined shr 18) or 240)
                        list.add(((combined shr 12) and 63) or 128)
                        list.add(((combined shr 6) and 63) or 128)
                        list.add((combined and 63) or 128)
                        i++
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

        var j: Long = 406644
        for (num in list) j = rl(j + num.toLong(), "+-a^+6")
        var result = rl(j, "+-3^+b+-f") xor 3293161072L
        if (result < 0) result = (result and 2147483647L) + 2147483648L
        val mod = result % 1_000_000L
        return "$mod.${406644L xor mod}"
    }

    private fun rl(value: Long, op: String): Long {
        var r = value
        var i = 0
        while (i < op.length - 2) {
            val d =
                if (op[i + 2] in 'a'..'z') op[i + 2].code - 'a'.code + 10
                else op[i + 2].digitToInt()
            val shift = if (op[i + 1] == '+') r ushr d else r shl d
            r = if (op[i] == '+') (r + shift) and 0xFFFFFFFFL else r xor shift
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

    private val filterTermTranslations =
        mapOf(
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
        return filterTermTranslations[text]
            ?: if (isChineseText(text)) translateText(text).ifEmpty { text } else text
    }

    private fun isChineseText(text: String): Boolean {
        val chineseCharCount =
            text.count { ch ->
                ch in '\u4e00'..'\u9fff' ||
                    ch in '\u3400'..'\u4dbf' ||
                    ch in '\uF900'..'\uFAFF' ||
                    ch in '\u3000'..'\u303f' ||
                    ch in '\uff00'..'\uffef'
            }
        return chineseCharCount > text.length * 0.3
    }
}

fun PreferenceScreen.addTranslationPreferences() {
    val preferences = this.preferences

    addPreference(
        SwitchPreferenceCompat(context).apply {
            key = Hanime1Translator.PREF_KEY_TRANSLATION_ENABLED
            title = "Enable Translation"
            summary = "Translate all Chinese text to English"
            setDefaultValue(false)
        },
    )

    addPreference(
        androidx.preference.ListPreference(context).apply {
            key = Hanime1Translator.PREF_KEY_TARGET_LANGUAGE
            title = "Target Language"
            entries = arrayOf("English", "繁體中文", "簡體中文", "日本語", "한국어")
            entryValues = arrayOf("en", "zh-TW", "zh-CN", "ja", "ko")
            setDefaultValue(Hanime1Translator.DEFAULT_TARGET_LANGUAGE)
            summary =
                "Current: ${preferences.getString(
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
                }}"
            setOnPreferenceChangeListener { _, newValue ->
                summary =
                    "Current: ${when (newValue as String) {
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
