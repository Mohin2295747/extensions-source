package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class Hanime1Translator {
    private val preferences: SharedPreferences by lazy {
        Injekt.get<android.app.Application>().getSharedPreferences("source_${Hanime1().id}", 0x0000)
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
        return preferences.getString(PREF_KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE) ?: DEFAULT_TARGET_LANGUAGE
    }

    suspend fun translateAnimeDetails(anime: SAnime): SAnime {
        if (!isTranslationEnabled()) return anime
        
        val translatedAnime = SAnime.create().apply {
            // Copy all basic properties first
            url = anime.url
            thumbnail_url = anime.thumbnail_url
        }
        
        runBlocking {
            try {
                // Translate title - REPLACE with English
                if (anime.title.isNotEmpty()) {
                    val translatedTitle = translateText(getTargetLanguage(), anime.title)
                    if (translatedTitle.isNotEmpty()) {
                        translatedAnime.title = translatedTitle
                    } else {
                        translatedAnime.title = anime.title
                    }
                }

                // Translate description - REPLACE with English
                if (anime.description.isNotEmpty()) {
                    val translatedDescription = translateText(getTargetLanguage(), anime.description)
                    if (translatedDescription.isNotEmpty()) {
                        translatedAnime.description = translatedDescription
                    } else {
                        translatedAnime.description = anime.description
                    }
                }

                // Translate author - REPLACE with English
                if (!anime.author.isNullOrEmpty()) {
                    val translatedAuthor = translateText(getTargetLanguage(), anime.author)
                    if (translatedAuthor.isNotEmpty()) {
                        translatedAnime.author = translatedAuthor
                    } else {
                        translatedAnime.author = anime.author
                    }
                }

                // Translate genre - REPLACE with English
                if (!anime.genre.isNullOrEmpty()) {
                    val translatedGenre = translateText(getTargetLanguage(), anime.genre)
                    if (translatedGenre.isNotEmpty()) {
                        translatedAnime.genre = translatedGenre
                    } else {
                        translatedAnime.genre = anime.genre
                    }
                }

                // Copy any untranslated properties
                translatedAnime.status = anime.status
                translatedAnime.initialized = anime.initialized

            } catch (e: Exception) {
                // If translation fails, return original anime
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
        
        // Split long text into chunks to avoid API limits
        val chunks = splitTextIntoChunks(text)
        val translatedChunks = mutableListOf<String>()
        
        for (chunk in chunks) {
            if (chunk.isBlank()) continue
            
            try {
                val url = buildTranslateUrl(targetLang, chunk)
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    responseBody?.let { body ->
                        try {
                            val jsonArray = JSONArray(body)
                            val translationArray = jsonArray.getJSONArray(0)
                            val translatedText = StringBuilder()
                            
                            for (i in 0 until translationArray.length()) {
                                val sentenceArray = translationArray.getJSONArray(i)
                                if (sentenceArray.length() > 0) {
                                    translatedText.append(sentenceArray.getString(0))
                                }
                            }
                            
                            if (translatedText.isNotEmpty()) {
                                translatedChunks.add(translatedText.toString())
                                continue // Success, move to next chunk
                            }
                        } catch (e: Exception) {
                            // JSON parsing failed
                        }
                    }
                }
            } catch (e: Exception) {
                // Translation request failed for this chunk
            }
            
            // If translation failed for this chunk, keep the original
            translatedChunks.add(chunk)
        }
        
        return translatedChunks.joinToString("")
    }

    private fun splitTextIntoChunks(text: String, maxChunkLength: Int = 1500): List<String> {
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()
        
        // Split by sentences if possible, otherwise by length
        val sentences = text.split('。', '！', '!', '？', '?', '\n').filter { it.isNotBlank() }
        
        if (sentences.size > 1) {
            for (sentence in sentences) {
                val sentenceWithPunctuation = sentence + if (text.contains(sentence + '。')) "。" else 
                    if (text.contains(sentence + '！')) "！" else 
                    if (text.contains(sentence + '!')) "!" else 
                    if (text.contains(sentence + '？')) "？" else 
                    if (text.contains(sentence + '?')) "?" else ""
                
                if (currentChunk.length + sentenceWithPunctuation.length <= maxChunkLength) {
                    currentChunk.append(sentenceWithPunctuation)
                } else {
                    if (currentChunk.isNotEmpty()) {
                        chunks.add(currentChunk.toString())
                        currentChunk.clear()
                    }
                    // If a single sentence is too long, split it by length
                    if (sentenceWithPunctuation.length > maxChunkLength) {
                        var longText = sentenceWithPunctuation
                        while (longText.length > maxChunkLength) {
                            chunks.add(longText.substring(0, maxChunkLength))
                            longText = longText.substring(maxChunkLength)
                        }
                        if (longText.isNotEmpty()) {
                            currentChunk.append(longText)
                        }
                    } else {
                        currentChunk.append(sentenceWithPunctuation)
                    }
                }
            }
            if (currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString())
            }
        } else {
            // Fallback: split by fixed length
            var remainingText = text
            while (remainingText.length > maxChunkLength) {
                chunks.add(remainingText.substring(0, maxChunkLength))
                remainingText = remainingText.substring(maxChunkLength)
            }
            if (remainingText.isNotEmpty()) {
                chunks.add(remainingText)
            }
        }
        
        return chunks
    }

    private fun buildTranslateUrl(targetLang: String, text: String): String {
        val client = "gtx"
        val token = calculateToken(text)
        
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            "https://translate.google.com/translate_a/single?client=$client&sl=auto&tl=$targetLang&dt=t&tk=$token&q=$encodedText"
        } catch (e: UnsupportedEncodingException) {
            // Fallback without encoding
            "https://translate.google.com/translate_a/single?client=$client&sl=auto&tl=$targetLang&dt=t&tk=$token&q=$text"
        }
    }

    private fun calculateToken(text: String): String {
        val list = mutableListOf<Int>()
        var i = 0

        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            when {
                codePoint < 128 -> list.add(codePoint)
                codePoint < 2048 -> {
                    list.add((codePoint shr 6) or 192)
                    list.add((codePoint and 63) or 128)
                }
                codePoint in 55296..57343 && i + 1 < text.length -> {
                    val nextCodePoint = text.codePointAt(i + 1)
                    if (nextCodePoint in 56320..57343) {
                        val combined = ((codePoint and 1023) shl 10) + (nextCodePoint and 1023) + 65536
                        list.add((combined shr 18) or 240)
                        list.add(((combined shr 12) and 63) or 128)
                        list.add(((combined shr 6) and 63) or 128)
                        list.add((combined and 63) or 128)
                        i++
                    }
                }
                else -> {
                    list.add((codePoint shr 12) or 224)
                    list.add(((codePoint shr 6) and 63) or 128)
                    list.add((codePoint and 63) or 128)
                }
            }
            i++
        }

        var j: Long = 406644
        for (num in list) {
            j = rl(j + num.toLong(), "+-a^+6")
        }
        
        var result = rl(j, "+-3^+b+-f") xor 3293161072L
        if (result < 0) {
            result = (result and 2147483647L) + 2147483648L
        }
        val modResult = result % 1000000L
        return "$modResult.${406644L xor modResult}"
    }

    private fun rl(value: Long, operation: String): Long {
        var result = value
        var i = 0
        while (i < operation.length - 2) {
            val d = if (operation[i + 2] in 'a'..'z') {
                operation[i + 2].code - 'a'.code + 10
            } else {
                operation[i + 2].digitToInt()
            }
            
            val shiftValue = if (operation[i + 1] == '+') {
                result ushr d
            } else {
                result shl d
            }
            
            result = if (operation[i] == '+') {
                (result + shiftValue) and 0xFFFFFFFFL
            } else {
                result xor shiftValue
            }
            i += 3
        }
        return result
    }
}

// Extension function to add translation preferences
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
        androidx.preference.ListPreference(context).apply {
            key = Hanime1Translator.PREF_KEY_TARGET_LANGUAGE
            title = "Target Language"
            entries = arrayOf("English", "繁體中文", "簡體中文", "日本語", "한국어")
            entryValues = arrayOf("en", "zh-TW", "zh-CN", "ja", "ko")
            setDefaultValue(Hanime1Translator.DEFAULT_TARGET_LANGUAGE)
            summary = "Current: ${preferences.getString(Hanime1Translator.PREF_KEY_TARGET_LANGUAGE, Hanime1Translator.DEFAULT_TARGET_LANGUAGE)?.let { 
                when (it) {
                    "en" -> "English"
                    "zh-TW" -> "繁體中文"
                    "zh-CN" -> "簡體中文"
                    "ja" -> "日本語"
                    "ko" -> "한국어"
                    else -> "English"
                }
            }}"
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
        }
    )
}
