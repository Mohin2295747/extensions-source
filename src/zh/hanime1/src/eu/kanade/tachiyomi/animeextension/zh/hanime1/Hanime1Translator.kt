package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class Hanime1Translator {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${Hanime1().id}", 0)
    }

    private val cachePreferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("hanime1_translation_cache", 0)
    }

    private val okHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    companion object {
        const val PREF_KEY_TRANSLATION_ENABLED = "pref_translation_enabled"
        const val PREF_KEY_TARGET_LANGUAGE = "pref_target_language"
        const val PREF_KEY_DEEPL_API_KEY = "pref_deepl_api_key"
        const val PREF_KEY_CLEAR_CACHE = "pref_clear_cache"
        const val DEFAULT_TARGET_LANGUAGE = "EN"
    }

    fun isTranslationEnabled(): Boolean {
        return preferences.getBoolean(PREF_KEY_TRANSLATION_ENABLED, false) &&
            getDeepLApiKey().isNotEmpty()
    }

    fun getTargetLanguage(): String {
        return preferences.getString(PREF_KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE)
            ?: DEFAULT_TARGET_LANGUAGE
    }

    fun getDeepLApiKey(): String {
        return preferences.getString(PREF_KEY_DEEPL_API_KEY, "") ?: ""
    }

    fun clearTranslationCache() {
        cachePreferences.edit().clear().apply()
    }

    fun getCacheSize(): String {
        val allEntries = cachePreferences.all
        var totalSize = 0L
        for ((_, value) in allEntries) {
            if (value is String) {
                totalSize += value.toByteArray(Charsets.UTF_8).size
            }
        }
        return when {
            totalSize < 1024 -> "${totalSize}B"
            totalSize < 1024 * 1024 -> "${totalSize / 1024}KB"
            else -> "${totalSize / (1024 * 1024)}MB"
        }
    }

    suspend fun translateAnimeDetails(anime: SAnime): SAnime {
        if (!isTranslationEnabled()) {
            return anime
        }

        return withContext(Dispatchers.IO) {
            try {
                val translatedAnime =
                    SAnime.create().apply {
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

                val originalDescription = anime.description
                if (!originalDescription.isNullOrEmpty()) {
                    val translatedDescription =
                        translateText(getTargetLanguage(), originalDescription)
                    translatedAnime.description =
                        translatedDescription.ifEmpty { originalDescription }
                } else {
                    translatedAnime.description = originalDescription
                }

                anime.author?.let { author ->
                    if (author.isNotEmpty()) {
                        val translatedAuthor = translateText(getTargetLanguage(), author)
                        translatedAnime.author = translatedAuthor.ifEmpty { author }
                    } else {
                        translatedAnime.author = author
                    }
                }

                anime.genre?.let { genre ->
                    if (genre.isNotEmpty()) {
                        val translatedGenre = translateText(getTargetLanguage(), genre)
                        translatedAnime.genre = translatedGenre.ifEmpty { genre }
                    } else {
                        translatedAnime.genre = genre
                    }
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
        if (text.isBlank()) return text

        // Check cache first
        val cacheKey = "${targetLang}_${text.hashCode()}"
        val cached = cachePreferences.getString(cacheKey, null)
        if (cached != null) {
            return cached
        }

        val chunks = splitTextIntoChunks(text)
        val translatedChunks = mutableListOf<String>()

        // Translate chunks sequentially to ensure all translations complete
        for (chunk in chunks) {
            val translatedChunk = translateSingleText(targetLang, chunk)
            translatedChunks.add(if (translatedChunk.isNotEmpty()) translatedChunk else chunk)
        }

        val result = translatedChunks.joinToString("")
        // Cache the result
        if (result.isNotEmpty() && result != text) {
            cachePreferences.edit().putString(cacheKey, result).apply()
        }

        return result.ifEmpty { text }
    }

    private suspend fun translateSingleText(targetLang: String, text: String): String {
        if (text.isBlank()) return text

        val apiKey = getDeepLApiKey()
        if (apiKey.isEmpty()) return text

        try {
            val formBody = FormBody.Builder()
                .add("auth_key", apiKey)
                .add("text", text)
                .add("target_lang", targetLang)
                .build()

            val request = Request.Builder()
                .url("https://api-free.deepl.com/v2/translate")
                .post(formBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.use { body ->
                    val responseText = body.string()
                    return parseDeepLResponse(responseText)
                }
            } else {
                // Log error for debugging
                println("DeepL API error: ${response.code} - ${response.message}")
            }
        } catch (e: Exception) {
            // Return original text on error
            println("Translation error: ${e.message}")
        }

        return text
    }

    private fun parseDeepLResponse(responseText: String): String {
        return try {
            val json = JSONObject(responseText)
            val translations = json.getJSONArray("translations")
            if (translations.length() > 0) {
                val firstTranslation = translations.getJSONObject(0)
                firstTranslation.getString("text")
            } else {
                ""
            }
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

        var currentPos = 0
        while (currentPos < text.length) {
            val nextEnder =
                sentenceEnders
                    .map { ender -> text.indexOf(ender, currentPos).takeIf { it != -1 } }
                    .filterNotNull()
                    .minOrNull()

            if (nextEnder == null) {
                val remaining = text.substring(currentPos)
                if (
                    currentChunk.isNotEmpty() &&
                    currentChunk.length + remaining.length > maxChunkLength
                ) {
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
        if (!isTranslationEnabled()) {
            return text
        }
        return filterTermTranslations[text]
            ?: if (isChineseText(text)) {
                translateText(text).ifEmpty { text }
            } else {
                text
            }
    }

    private fun isChineseText(text: String): Boolean {
        if (text.isBlank()) return false

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
    val context = this.context
    val translator = Hanime1Translator()

    addPreference(
        SwitchPreferenceCompat(context).apply {
            key = Hanime1Translator.PREF_KEY_TRANSLATION_ENABLED
            title = "Enable Translation"
            summary = "Translate all Chinese text to target language using DeepL"
            setDefaultValue(false)
        },
    )

    addPreference(
        EditTextPreference(context).apply {
            key = Hanime1Translator.PREF_KEY_DEEPL_API_KEY
            title = "DeepL API Key"
            summary = "Enter your DeepL API key for translation"
            setDefaultValue("")
            dialogTitle = "DeepL API Key"
            dialogMessage = "Get your free API key from https://www.deepl.com/pro-api"
        },
    )

    addPreference(
        ListPreference(context).apply {
            key = Hanime1Translator.PREF_KEY_TARGET_LANGUAGE
            title = "Target Language"
            entries = arrayOf("English", "繁體中文", "簡體中文", "日本語", "한국어")
            entryValues = arrayOf("EN", "ZH", "ZH", "JA", "KO")
            setDefaultValue(Hanime1Translator.DEFAULT_TARGET_LANGUAGE)

            val currentLang = translator.getTargetLanguage()
            summary = "Current: ${getLanguageDisplayName(currentLang)}"

            setOnPreferenceChangeListener { _, newValue ->
                summary = "Current: ${getLanguageDisplayName(newValue as String)}"
                true
            }
        },
    )

    addPreference(
        Preference().apply {
            key = Hanime1Translator.PREF_KEY_CLEAR_CACHE
            title = "Clear Translation Cache"
            summary = "Current cache size: ${translator.getCacheSize()}\nClick to clear cached translations"
            setOnPreferenceClickListener {
                translator.clearTranslationCache()
                // Update the summary after clearing
                summary = "Current cache size: ${translator.getCacheSize()}\nClick to clear cached translations"
                true
            }
        },
    )
}

private fun getLanguageDisplayName(lang: String?): String {
    return when (lang) {
        "EN" -> "English"
        "ZH" -> "Chinese"
        "JA" -> "Japanese"
        "KO" -> "Korean"
        else -> "English"
    }
}
