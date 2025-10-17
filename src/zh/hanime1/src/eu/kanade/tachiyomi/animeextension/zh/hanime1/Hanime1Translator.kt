package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class Hanime1Translator {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>()
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

    fun isTranslationEnabled(): Boolean {
        return preferences.getBoolean(PREF_KEY_TRANSLATION_ENABLED, false)
    }

    fun getTargetLanguage(): String {
        return preferences.getString(PREF_KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE)
            ?: DEFAULT_TARGET_LANGUAGE
    }

    suspend fun translateAnimeDetails(anime: SAnime): SAnime {
        if (!isTranslationEnabled()) {
            return anime
        }

        return withContext(Dispatchers.IO) {
            try {
                val translatedAnime = SAnime.create().apply {
                    url = anime.url
                    thumbnail_url = anime.thumbnail_url
                    status = anime.status
                    initialized = anime.initialized
                }

                if (anime.title.isNotEmpty()) {
                    val translatedTitle = translateText(getTargetLanguage(), anime.title)
                    translatedAnime.title = translatedTitle.ifEmpty { anime.title }
                } else {
                    translatedAnime.title = anime.title
                }

                if (anime.description.isNotEmpty()) {
                    val translatedDescription = translateText(getTargetLanguage(), anime.description)
                    translatedAnime.description = translatedDescription.ifEmpty { anime.description }
                } else {
                    translatedAnime.description = anime.description
                }

                anime.author?.let { author ->
                    val translatedAuthor = translateText(getTargetLanguage(), author)
                    translatedAnime.author = translatedAuthor.ifEmpty { author }
                }

                anime.genre?.let { genre ->
                    val translatedGenre = translateText(getTargetLanguage(), genre)
                    translatedAnime.genre = translatedGenre.ifEmpty { genre }
                }

                translatedAnime
            } catch (e: Exception) {
                anime
            }
        }
    }

    suspend fun translateText(text: String): String {
        if (text.isBlank()) {
            return text
        }
        return translateText(getTargetLanguage(), text)
    }

    private suspend fun translateText(targetLang: String, text: String): String {
        if (text.isBlank()) {
            return text
        }

        val chunks = splitTextIntoChunks(text)
        val translatedChunks = mutableListOf<String>()

        for (chunk in chunks) {
            if (chunk.isBlank()) {
                translatedChunks.add(chunk)
                continue
            }

            try {
                val url = buildTranslateUrl(targetLang, chunk)
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.use { body ->
                        val responseText = body.string()
                        val translated = parseTranslationResponse(responseText)
                        if (translated.isNotEmpty()) {
                            translatedChunks.add(translated)
                            continue
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue with original text on error
            }
            translatedChunks.add(chunk)
        }
        return translatedChunks.joinToString("")
    }

    private fun parseTranslationResponse(responseText: String): String {
        return try {
            val jsonArray = JSONArray(responseText)
            val translationArray = jsonArray.getJSONArray(0)
            val builder = StringBuilder()
            for (i in 0 until translationArray.length()) {
                val sentence = translationArray.getJSONArray(i)
                if (sentence.length() > 0) {
                    builder.append(sentence.getString(0))
                }
            }
            builder.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun splitTextIntoChunks(text: String, maxChunkLength: Int = 1500): List<String> {
        if (text.length <= maxChunkLength) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()
        val sentenceEnders = setOf('。', '！', '!', '？', '?', '\n')

        // Try to split by sentences first
        var currentPos = 0
        while (currentPos < text.length) {
            val nextEnder = sentenceEnders.map { ender ->
                text.indexOf(ender, currentPos).takeIf { it != -1 }
            }.filterNotNull().minOrNull()

            if (nextEnder == null) {
                // No more sentence enders, add remaining text
                val remaining = text.substring(currentPos)
                if (currentChunk.isNotEmpty() && currentChunk.length + remaining.length > maxChunkLength) {
                    chunks.add(currentChunk.toString())
                    currentChunk = StringBuilder(remaining)
                } else {
                    currentChunk.append(remaining)
                }
                break
            }

            val sentenceEnd = nextEnder + 1
            val sentence = text.substring(currentPos, sentenceEnd.coerceAtMost(text.length))

            if (currentChunk.length + sentence.length > maxChunkLength) {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString())
                    currentChunk = StringBuilder()
                }
                // If single sentence is too long, split by character limit
                if (sentence.length > maxChunkLength) {
                    var remainingSentence = sentence
                    while (remainingSentence.length > maxChunkLength) {
                        chunks.add(remainingSentence.substring(0, maxChunkLength))
                        remainingSentence = remainingSentence.substring(maxChunkLength)
                    }
                    if (remainingSentence.isNotEmpty()) {
                        currentChunk.append(remainingSentence)
                    }
                } else {
                    currentChunk.append(sentence)
                }
            } else {
                currentChunk.append(sentence)
            }

            currentPos = sentenceEnd
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString())
        }

        return chunks
    }

    private fun buildTranslateUrl(targetLang: String, text: String): String {
        val client = "gtx"
        val token = calculateToken(text)
        val encodedText = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            text
        }
        return "https://translate.google.com/translate_a/single?client=$client&sl=auto&tl=$targetLang&dt=t&tk=$token&q=$encodedText"
    }

    private fun calculateToken(text: String): String {
        var tkk0 = 406644L
        var tkk1 = 3293161072L

        val bytes = text.toByteArray(Charsets.UTF_8)
        var accumulator = tkk0.toInt()

        for (byte in bytes) {
            accumulator += byte.toInt() and 0xFF
            accumulator = rl(accumulator, "+-a^+6")
        }

        accumulator = rl(accumulator, "+-3^+b+-f")
        accumulator = accumulator xor tkk1.toInt()

        if (accumulator < 0) {
            accumulator = (accumulator and Int.MAX_VALUE) + Int.MAX_VALUE + 1
        }

        val mod = accumulator % 1000000
        return "$mod.${tkk0.toInt() xor mod}"
    }

    private fun rl(value: Int, op: String): Int {
        var result = value
        var i = 0
        while (i < op.length - 2) {
            val operator = op[i]
            val direction = op[i + 1]
            val shiftChar = op[i + 2]

            val shiftAmount = if (shiftChar in 'a'..'z') {
                shiftChar - 'a' + 10
            } else {
                shiftChar - '0'
            }

            val shift = when (direction) {
                '+' -> result ushr shiftAmount
                '-' -> result shl shiftAmount
                else -> 0
            }

            result = when (operator) {
                '+' -> (result + shift) and 0xFFFFFFFF.toInt()
                '-' -> (result xor shift) and 0xFFFFFFFF.toInt()
                else -> result
            }

            i += 3
        }
        return result
    }

    suspend fun translateFilterValues(values: List<String>): List<String> {
        if (!isTranslationEnabled()) {
            return values
        }
        return values.map { value ->
            if (isChineseText(value)) {
                translateText(value).ifEmpty { value }
            } else {
                value
            }
        }
    }

    suspend fun translateFilterName(name: String): String {
        if (!isTranslationEnabled()) {
            return name
        }
        return if (isChineseText(name)) {
            translateText(name).ifEmpty { name }
        } else {
            name
        }
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
        if (!isTranslationEnabled()) {
            return text
        }
        return filterTermTranslations[text] ?: if (isChineseText(text)) {
            translateText(text).ifEmpty { text }
        } else {
            text
        }
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

fun PreferenceScreen.addTranslationPreferences() {
    addPreference(
        SwitchPreferenceCompat(context).apply {
            key = Hanime1Translator.PREF_KEY_TRANSLATION_ENABLED
            title = "Enable Translation"
            summary = "Translate all Chinese text to English"
            setDefaultValue(false)
        }
    )

    addPreference(
        ListPreference(context).apply {
            key = Hanime1Translator.PREF_KEY_TARGET_LANGUAGE
            title = "Target Language"
            entries = arrayOf("English", "繁體中文", "簡體中文", "日本語", "한국어")
            entryValues = arrayOf("en", "zh-TW", "zh-CN", "ja", "ko")
            setDefaultValue(Hanime1Translator.DEFAULT_TARGET_LANGUAGE)

            val currentLang = preferences.getString(key, Hanime1Translator.DEFAULT_TARGET_LANGUAGE)
            summary = "Current: ${getLanguageDisplayName(currentLang)}"

            setOnPreferenceChangeListener { _, newValue ->
                summary = "Current: ${getLanguageDisplayName(newValue as String)}"
                true
            }
        }
    )
}

private fun getLanguageDisplayName(lang: String?): String {
    return when (lang) {
        "en" -> "English"
        "zh-TW" -> "繁體中文"
        "zh-CN" -> "簡體中文"
        "ja" -> "日本語"
        "ko" -> "한국어"
        else -> "English"
    }
}
