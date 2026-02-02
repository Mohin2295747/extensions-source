@file:Suppress("PropertyName", "NoTrailingSpaces", "Wrapping")

package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object CloudflareHelper {
    const val BASE_URL = "https://hanime1.me"
    const val TAG = "Hanime1-Cloudflare"
    private val json by injectLazy<Json>()
    private val network: NetworkHelper by injectLazy()

    @Serializable
    enum class BlockType {
        NONE,
        COOKIE_EXPIRED,
        CLOUDFLARE_CHALLENGE,
        AGE_VERIFICATION,
        RATE_LIMIT,
        NETWORK_ERROR,
        UNKNOWN,
    }

    @Serializable
    data class BlockInfo(
        val type: BlockType,
        val message: String,
        val solution: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val MAX_RETRIES = 2

    private val browserHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8,zh-TW;q=0.7",
        "Accept-Encoding" to "gzip, deflate",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "DNT" to "1",
        "Cache-Control" to "max-age=0",
    )

    private var lastReferer = BASE_URL
    private var blockHistory = mutableListOf<BlockInfo>()

    fun createClient(): OkHttpClient {
        return network.cloudflareClient.newBuilder()
            .addInterceptor(::authInterceptor)
            .addInterceptor(::refererInterceptor)
            .addInterceptor(::errorDetectionInterceptor)
            .addInterceptor(::retryInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun errorDetectionInterceptor(chain: Interceptor.Chain): Response {
        try {
            val response = chain.proceed(chain.request())

            if (!response.isSuccessful) {
                val requestUrl = chain.request().url.toString()
                val errorInfo = when (response.code) {
                    403 -> BlockInfo(
                        BlockType.CLOUDFLARE_CHALLENGE,
                        "Access denied (403)",
                        "Cookies may have expired. Please re-import fresh cookies.",
                    )

                    429 -> BlockInfo(
                        BlockType.RATE_LIMIT,
                        "Rate limited (429)",
                        "Too many requests. Wait a few minutes and try again.",
                    )

                    503 -> BlockInfo(
                        BlockType.CLOUDFLARE_CHALLENGE,
                        "Service unavailable (503)",
                        "Cloudflare protection active. Try importing fresh cookies.",
                    )

                    else -> BlockInfo(
                        BlockType.UNKNOWN,
                        "HTTP ${response.code} error",
                        "Please check your connection and try again.",
                    )
                }

                logBlock(errorInfo, requestUrl)
                saveBlockInfo(errorInfo)
            }

            return response
        } catch (e: Exception) {
            logBlock(
                BlockInfo(
                    BlockType.NETWORK_ERROR,
                    "Network error: ${e.message}",
                    "Check your internet connection and try again.",
                ),
                chain.request().url.toString(),
            )
            throw e
        }
    }

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        val preferences = getPreferences()
        val customUa = preferences?.getString("PREF_KEY_CUSTOM_UA", null)
        builder.header(
            "User-Agent",
            customUa?.takeIf { it.isNotBlank() } ?: DESKTOP_USER_AGENT,
        )

        if (original.url.pathSegments.contains("search") || original.url.pathSegments.contains("watch")) {
            builder.header("Origin", BASE_URL)
        }

        browserHeaders.forEach { (key, value) ->
            builder.header(key, value)
        }

        return chain.proceed(builder.build())
    }

    private fun refererInterceptor(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        if (original.url.pathSegments.contains("search") || original.url.pathSegments.contains("watch")) {
            builder.header("Referer", lastReferer)
        }

        val response = chain.proceed(builder.build())

        if (original.url.pathSegments.contains("search") || original.url.pathSegments.contains("watch")) {
            lastReferer = original.url.toString()
        }

        return response
    }

    private fun retryInterceptor(chain: Interceptor.Chain): Response {
        var attempt = 0
        var response: Response

        do {
            val request = chain.request()
            response = chain.proceed(request)

            if (response.isSuccessful || attempt >= MAX_RETRIES || response.code in listOf(403, 429, 503)) {
                return response
            }

            attempt++
            Log.w(TAG, "Retry attempt $attempt for ${request.url}")
            Thread.sleep((1000L * attempt).coerceAtMost(3000L))

            response.close()
        } while (attempt < MAX_RETRIES)

        return response
    }

    fun analyzeBlock(
        response: Response,
        document: Document,
        expectedSelector: String,
    ): BlockInfo {
        val text = document.text().lowercase(Locale.getDefault())
        val title = document.select("title").text().lowercase(Locale.getDefault())

        return when {
            text.contains("cloudflare") || title.contains("cloudflare") ->
                BlockInfo(
                    BlockType.CLOUDFLARE_CHALLENGE,
                    "Cloudflare protection active",
                    "This site uses Cloudflare bot protection.\n\n1. Open Hanime1 in WebView\n2. Log in/complete any CAPTCHA\n3. Import cookies from WebView",
                )

            text.contains("age verification") || text.contains("age check") ->
                BlockInfo(
                    BlockType.AGE_VERIFICATION,
                    "Age verification required",
                    "You need to verify your age on the website first.\n\n1. Open Hanime1 in browser\n2. Complete age verification\n3. Import fresh cookies",
                )

            response.code == 403 && document.select(expectedSelector).isEmpty() && document.select("video").isEmpty() ->
                BlockInfo(
                    BlockType.COOKIE_EXPIRED,
                    "Cookies expired or invalid",
                    "Your cookies have expired or are no longer valid.\n\nSolution:\n1. Clear current cookies\n2. Open WebView\n3. Log in again\n4. Import new cookies",
                )

            response.code == 429 ->
                BlockInfo(
                    BlockType.RATE_LIMIT,
                    "Rate limited - Too many requests",
                    "You're making too many requests.\n\nPlease wait 5-10 minutes before trying again.\nConsider using the 'Popular' or 'Latest' tabs instead of frequent searches.",
                )

            else ->
                BlockInfo(
                    BlockType.UNKNOWN,
                    "Access blocked (HTTP ${response.code})",
                    "Unable to access content.\n\nPossible solutions:\n1. Check if site is accessible in browser\n2. Clear and re-import cookies\n3. Try again later\n4. Contact extension maintainer if issue persists",
                )
        }
    }

    fun checkDocumentForBlock(
        document: Document,
        expectedSelector: String,
    ): BlockInfo? {
        val text = document.text().lowercase(Locale.getDefault())
        val html = document.html().lowercase(Locale.getDefault())

        return when {
            html.contains("cloudflare") || text.contains("cloudflare") ||
                html.contains("verify you are human") || text.contains("verify you are human") ||
                html.contains("checking your browser") || text.contains("checking your browser") -> {
                BlockInfo(
                    BlockType.CLOUDFLARE_CHALLENGE,
                    "Cloudflare protection detected",
                    "Open Hanime1 in AniYomi's WebView first to solve Cloudflare challenge.",
                )
            }

            html.contains("age verification") || text.contains("age verification") ||
                html.contains("age check") || text.contains("age check") -> {
                BlockInfo(
                    BlockType.AGE_VERIFICATION,
                    "Age verification required",
                    "Visit hanime1.me in a browser and complete age verification first.",
                )
            }

            text.contains("rate limit") || text.contains("too many requests") ||
                html.contains("429") || text.contains("429") -> {
                BlockInfo(
                    BlockType.RATE_LIMIT,
                    "Rate limited",
                    "Too many requests. Wait 5-10 minutes before trying again.",
                )
            }

            document.select(expectedSelector).isEmpty() &&
                document.select("video").isEmpty() &&
                document.select("source[type=\"video/mp4\"]").isEmpty() -> {
                BlockInfo(
                    BlockType.COOKIE_EXPIRED,
                    "Content not found - Cookies may have expired",
                    "Re-open Hanime1 in WebView to refresh cookies.",
                )
            }

            else -> null
        }
    }

    fun saveBlockInfo(preferences: SharedPreferences, blockInfo: BlockInfo) {
        blockHistory.add(blockInfo)

        if (blockHistory.size > 10) {
            blockHistory = blockHistory.takeLast(10).toMutableList()
        }

        preferences.edit()?.apply {
            putBoolean("PREF_KEY_COOKIE_INVALID", true)
            putString("last_block_info", json.encodeToString(blockInfo))
            apply()
        }
    }

    fun getLastBlockInfo(preferences: SharedPreferences): BlockInfo? {
        val jsonStr = preferences.getString("last_block_info", null)
        return if (!jsonStr.isNullOrBlank()) {
            try {
                json.decodeFromString<BlockInfo>(jsonStr)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    fun clearBlockStatus(preferences: SharedPreferences) {
        preferences.edit()
            .remove("last_block_info")
            .putLong("last_success_time", System.currentTimeMillis())
            .apply()
    }

    private fun logBlock(blockInfo: BlockInfo, url: String) {
        Log.w(TAG, "Block detected on $url: ${blockInfo.type} - ${blockInfo.message}")
    }

    fun parseCookies(cookieStr: String): List<Cookie> {
        return try {
            if (cookieStr.trim().startsWith("[")) {
                val cookieList = json.decodeFromString<List<JsonElement>>(cookieStr)
                val cookies = mutableListOf<Cookie>()
                val httpUrl = BASE_URL.toHttpUrl()

                for (cookieJson in cookieList) {
                    try {
                        val obj = cookieJson.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content ?: continue
                        val value = obj["value"]?.jsonPrimitive?.content ?: continue
                        val domain = obj["domain"]?.jsonPrimitive?.content ?: ".hanime1.me"
                        val path = obj["path"]?.jsonPrimitive?.content ?: "/"
                        val secure = obj["secure"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        val httpOnly = obj["httpOnly"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                        val cookie = Cookie.Builder()
                            .name(name)
                            .value(value)
                            .domain(domain)
                            .path(path)
                            .apply {
                                if (secure) secure()
                                if (httpOnly) httpOnly()
                            }
                            .build()

                        cookies.add(cookie)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse cookie: ${e.message}")
                    }
                }
                cookies
            } else {
                parseRawCookies(cookieStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cookies: ${e.message}")
            parseRawCookies(cookieStr)
        }
    }

    private fun parseRawCookies(cookieStr: String): List<Cookie> {
        val cookies = mutableListOf<Cookie>()
        val httpUrl = BASE_URL.toHttpUrl()

        cookieStr.split(";").forEach { cookiePair ->
            val trimmed = cookiePair.trim().replace("\n", "").replace("\r", "")
            if (trimmed.isNotEmpty()) {
                try {
                    val cookie = Cookie.parse(httpUrl, trimmed)
                    if (cookie != null) {
                        cookies.add(cookie)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse raw cookie '$trimmed': ${e.message}")
                }
            }
        }
        return cookies
    }

    fun setLanguageCookie(language: String) {
        try {
            val httpUrl = BASE_URL.toHttpUrl()
            val cookie = Cookie.Builder()
                .name("user_lang")
                .value(language)
                .domain(".hanime1.me")
                .path("/")
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set language cookie: ${e.message}")
        }
    }

    fun clearAllCookies(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean("PREF_KEY_COOKIE_INVALID", false)
            .remove("last_block_info")
            .apply()
        clearBlockHistory()
    }

    fun clearBlockHistory() {
        blockHistory.clear()
    }

    fun formatBlockHistory(): String {
        if (blockHistory.isEmpty()) return "No recent blocks"

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return blockHistory.joinToString("\n\n") { block ->
            "⚠ ${block.type}\nTime: ${sdf.format(Date(block.timestamp))}\nIssue: ${block.message}\nSolution: ${block.solution}"
        }
    }

    fun getBlockHistory(): List<BlockInfo> {
        return blockHistory.takeLast(10)
    }

    fun getDetailedHelp(context: android.content.Context): String {
        return "ℹ️ **Hanime1 Extension Help**\n\n**Common Issues & Solutions:**\n\n1. **Cloudflare Blocked (403/503)**\n   - Open Hanime1 in WebView\n   - Complete any CAPTCHA/verification\n   - Import cookies from WebView\n\n2. **Age Verification Required**\n   - Visit hanime1.me in browser first\n   - Complete age verification\n   - Import fresh cookies\n\n3. **Rate Limited (429)**\n   - Wait 5-10 minutes\n   - Avoid rapid searches\n   - Use Popular/Latest tabs\n\n4. **Cookies Expire Frequently**\n   - This is normal (1-7 days)\n   - Re-import when needed\n   - Consider browser bookmark\n\n**Tips:**\n• Keep cookies up-to-date\n• Use English filters if Chinese fails\n• Try 'Broad Match' in search filters\n• Clear cookies before fresh import\n\n**Need More Help?**\nContact extension maintainer or check Tachiyomi Discord."
    }

    private fun getPreferences(): SharedPreferences? {
        return try {
            val context = Injekt.get<Application>()
            context.getSharedPreferences("source_${getSourceId()}", 0x0000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get preferences: ${e.message}")
            null
        }
    }

    private fun getSourceId(): Long {
        return 1234567890L
    }

    private fun saveBlockInfo(blockInfo: BlockInfo) {
        blockHistory.add(blockInfo)

        if (blockHistory.size > 10) {
            blockHistory = blockHistory.takeLast(10).toMutableList()
        }

        val prefs = getPreferences()
        prefs?.edit()?.apply {
            putBoolean("PREF_KEY_COOKIE_INVALID", true)
            putString("last_block_info", json.encodeToString(blockInfo))
            apply()
        }
    }

    // Exception class for Cloudflare blocks
    class BlockedException(val blockInfo: BlockInfo) : Exception(
        "${blockInfo.type}: ${blockInfo.message}\nSolution: ${blockInfo.solution}",
    )
}
