package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume

class ChineseTranslator(private val context: Context) {
    private var translator: com.google.mlkit.nl.translate.Translator? = null
    private val languageIdentifier = LanguageIdentification.getClient()
    private val translationCache = mutableMapOf<String, String>()

    suspend fun translate(text: String): String {
        if (text.isBlank()) return text

        translationCache[text]?.let { return it }

        val detectedLang = detectLanguage(text)
        if (detectedLang != "zh" && detectedLang != "zh-CN" && detectedLang != "zh-TW") {
            return text
        }

        if (translator == null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            translator = Translation.getClient(options)

            try {
                translator!!.downloadModelIfNeeded().await()
            } catch (e: Exception) {
                return text
            }
        }

        return try {
            val translated = translator!!.translate(text).await()
            translationCache[text] = translated
            translated
        } catch (e: Exception) {
            text
        }
    }

    private suspend fun detectLanguage(text: String): String {
        return suspendCancellableCoroutine { continuation ->
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { languageCode ->
                    continuation.resume(languageCode)
                }
                .addOnFailureListener {
                    continuation.resume("und")
                }
        }
    }

    fun cleanup() {
        translator?.close()
        languageIdentifier.close()
    }
}

class Hanime1 : AnimeHttpSource(), ConfigurableAnimeSource {
    private val translator by lazy {
        ChineseTranslator(Injekt.get<Application>().applicationContext)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        val translatePref = ListPreference(screen.context).apply {
            key = PREF_TRANSLATE_KEY
            title = "Translation"
            entries = arrayOf("Disabled", "English")
            entryValues = arrayOf("false", "true")
            summary = "%s"
            setDefaultValue("false")
        }

        screen.addPreference(translatePref)
    }

    companion object {
        private const val PREF_TRANSLATE_KEY = "pref_translate_enabled"
    }

    private suspend fun translateIfEnabled(text: String): String {
        val prefs = Injekt.get<SharedPreferences>()
        return if (prefs.getBoolean(PREF_TRANSLATE_KEY, false)) {
            translator.translate(text)
        } else {
            text
        }
    }

    override suspend fun animeDetailsParse(response: Response): SAnime {
        val jsoup = response.asJsoup()
        return SAnime.create().apply {
            genre = jsoup.select(".single-video-tag")
                .not("[data-toggle]")
                .eachText()
                .joinToString { runBlocking { translateIfEnabled(it) } }

            author = runBlocking {
                translateIfEnabled(jsoup.select("#video-artist-name").text())
            }

            jsoup.select("script[type=application/ld+json]").first()?.data()?.let {
                val info = json.decodeFromString<JsonElement>(it).jsonObject

                title = runBlocking {
                    translateIfEnabled(info["name"]!!.jsonPrimitive.content)
                }

                description = runBlocking {
                    translateIfEnabled(info["description"]!!.jsonPrimitive.content)
                }
            }
        }
    }

    override suspend fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("div.search-doujin-videos.hidden-xs")
        val list = if (nodes.isNotEmpty()) {
            nodes.map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.select("a[class=overlay]").attr("href"))
                    thumbnail_url = it.select("img + img").attr("src")

                    title = runBlocking {
                        translateIfEnabled(it.select("div.card-mobile-title").text())
                    }.appendInvisibleChar()

                    author = runBlocking {
                        translateIfEnabled(it.select(".card-mobile-user").text())
                    }
                }
            }
        } else {
            jsoup.select("a:not([target]) > .search-videos").map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.parent()!!.attr("href"))
                    thumbnail_url = it.select("img").attr("src")

                    title = runBlocking {
                        translateIfEnabled(it.select(".home-rows-videos-title").text())
                    }.appendInvisibleChar()
                }
            }
        }
        val nextPage = jsoup.select("li.page-item a.page-link[rel=next]")
        return AnimesPage(list, nextPage.isNotEmpty())
    }
}
