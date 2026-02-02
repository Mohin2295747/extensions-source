package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object CloudflareHelper {
    private const val TAG = "Hanime1-CF"
    private const val BASE_URL = "https://hanime1.me"
    
    private val json: Json by injectLazy()
    private val network: NetworkHelper by injectLazy()
    
    @Serializable
    data class BlockInfo(
        val type: BlockType,
        val message: String,
        val solution: String,
        val timestamp: Long = System.currentTimeMillis(),
    )
    
    @Serializable
    enum class BlockType {
        NONE,
        CLOUDFLARE,
        AGE_VERIFICATION,
        RATE_LIMIT,
        NETWORK_ERROR,
        COOKIE_EXPIRED,
        UNKNOWN
    }
    
    // CRITICAL: Use the cloudflareClient, not regular client
    fun createClient(): OkHttpClient {
        return network.cloudflareClient.newBuilder()
            .addInterceptor(::browserHeadersInterceptor)
            .addInterceptor(::errorInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    // Minimal, necessary headers only
    private fun browserHeadersInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val newRequest = request.newBuilder()
            .apply {
                // Use same User-Agent as WebView
                val existingUa = request.header("User-Agent")
                if (existingUa.isNullOrEmpty()) {
                    header("User-Agent", DEFAULT_USER_AGENT)
                }
                
                // Essential headers for hanime1
                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                header("Accept-Encoding", "gzip, deflate")
                header("Connection", "keep-alive")
                header("Upgrade-Insecure-Requests", "1")
                header("Sec-Fetch-Dest", "document")
                header("Sec-Fetch-Mode", "navigate")
                header("Sec-Fetch-Site", "same-origin")
                header("Sec-Fetch-User", "?1")
                
                // Set Referer if not present
                if (request.header("Referer").isNullOrEmpty() && request.url.toString().contains("hanime1")) {
                    header("Referer", BASE_URL)
                }
            }
            .build()
        
        return chain.proceed(newRequest)
    }
    
    private fun errorInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            throw Exception("Network error: ${e.message}", e)
        }
        
        // Check for Cloudflare/block responses
        if (isBlockedResponse(response)) {
            response.close()
            handleBlockedResponse(request.url.toString(), response)
        }
        
        return response
    }
    
    private fun isBlockedResponse(response: Response): Boolean {
        // Check HTTP status codes
        if (response.code in listOf(403, 429, 503)) {
            return true
        }
        
        // Check content-type and body for Cloudflare
        val contentType = response.header("Content-Type", "")
        if (contentType?.contains("text/html") == true) {
            val body = response.peekBody(1024).string()
            if (body.contains("Cloudflare", ignoreCase = true) ||
                body.contains("cf-error-details", ignoreCase = true) ||
                body.contains("verify you are human", ignoreCase = true) ||
                body.contains("Please turn JavaScript on", ignoreCase = true) ||
                body.contains("Checking your browser", ignoreCase = true)) {
                return true
            }
        }
        
        return false
    }
    
    fun checkDocumentForBlock(document: Document, expectedSelector: String): BlockInfo? {
        val text = document.text().lowercase(Locale.getDefault())
        val html = document.html().lowercase(Locale.getDefault())
        
        return when {
            html.contains("cloudflare") || text.contains("cloudflare") ||
            html.contains("verify you are human") || text.contains("verify you are human") ||
            html.contains("checking your browser") || text.contains("checking your browser") -> {
                BlockInfo(
                    BlockType.CLOUDFLARE,
                    "Cloudflare protection detected",
                    "Open Hanime1 in AniYomi's WebView first to solve Cloudflare challenge."
                )
            }
            
            html.contains("age verification") || text.contains("age verification") ||
            html.contains("age check") || text.contains("age check") -> {
                BlockInfo(
                    BlockType.AGE_VERIFICATION,
                    "Age verification required",
                    "Visit hanime1.me in a browser and complete age verification first."
                )
            }
            
            text.contains("rate limit") || text.contains("too many requests") ||
            html.contains("429") || text.contains("429") -> {
                BlockInfo(
                    BlockType.RATE_LIMIT,
                    "Rate limited",
                    "Too many requests. Wait 5-10 minutes before trying again."
                )
            }
            
            document.select(expectedSelector).isEmpty() &&
            document.select("video").isEmpty() &&
            document.select("source[type=\"video/mp4\"]").isEmpty() -> {
                BlockInfo(
                    BlockType.COOKIE_EXPIRED,
                    "Content not found - Cookies may have expired",
                    "Re-open Hanime1 in WebView to refresh cookies."
                )
            }
            
            else -> null
        }
    }
    
    private fun handleBlockedResponse(url: String, response: Response): Nothing {
        val contentType = response.header("Content-Type", "")
        val code = response.code
        
        val blockInfo = when {
            code == 403 || code == 503 -> BlockInfo(
                BlockType.CLOUDFLARE,
                "Access denied ($code) - Cloudflare protection",
                "Open this source in AniYomi WebView to solve Cloudflare challenge."
            )
            
            code == 429 -> BlockInfo(
                BlockType.RATE_LIMIT,
                "Rate limited ($code) - Too many requests",
                "Wait 5-10 minutes before trying again."
            )
            
            contentType?.contains("text/html") == true -> {
                val body = response.peekBody(2048).string()
                when {
                    body.contains("Cloudflare", ignoreCase = true) ||
                    body.contains("cf-error-details", ignoreCase = true) -> BlockInfo(
                        BlockType.CLOUDFLARE,
                        "Cloudflare challenge detected",
                        "Open Hanime1 in WebView to complete CAPTCHA."
                    )
                    
                    body.contains("Age Verification", ignoreCase = true) -> BlockInfo(
                        BlockType.AGE_VERIFICATION,
                        "Age verification required",
                        "Complete age verification on hanime1.me in a browser first."
                    )
                    
                    else -> BlockInfo(
                        BlockType.UNKNOWN,
                        "Blocked ($code)",
                        "Try opening the source in WebView first."
                    )
                }
            }
            
            else -> BlockInfo(
                BlockType.UNKNOWN,
                "Blocked (HTTP $code)",
                "Unknown blocking reason. Try WebView."
            )
        }
        
        logBlock(blockInfo, url)
        throw BlockedException(blockInfo)
    }
    
    // Simple logger
    private fun logBlock(blockInfo: BlockInfo, url: String) {
        Log.w(TAG, "[${blockInfo.type}] ${blockInfo.message} - URL: $url")
        Log.w(TAG, "Solution: ${blockInfo.solution}")
    }
    
    // Clear block status (call this when you successfully get content)
    fun clearBlockStatus(preferences: SharedPreferences) {
        preferences.edit()
            .remove("last_block_info")
            .putLong("last_success_time", System.currentTimeMillis())
            .apply()
    }
    
    // Save block info to preferences
    fun saveBlockInfo(preferences: SharedPreferences, blockInfo: BlockInfo) {
        preferences.edit()
            .putString("last_block_info", json.encodeToString(blockInfo))
            .apply()
    }
    
    // Get last block info
    fun getLastBlockInfo(preferences: SharedPreferences): BlockInfo? {
        val jsonStr = preferences.getString("last_block_info", null)
        return if (!jsonStr.isNullOrEmpty()) {
            try {
                json.decodeFromString<BlockInfo>(jsonStr)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    
    // Format block info for display
    fun formatBlockInfo(blockInfo: BlockInfo): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(blockInfo.timestamp))
        
        return """
            ⚠ ${blockInfo.type}
            Time: $time
            Issue: ${blockInfo.message}
            Solution: ${blockInfo.solution}
        """.trimIndent()
    }
    
    // Simple exception to throw when blocked
    class BlockedException(val blockInfo: BlockInfo) : Exception(
        "${blockInfo.type}: ${blockInfo.message}\nSolution: ${blockInfo.solution}"
    )
    
    // Compatible User-Agent (similar to WebView)
    private const val DEFAULT_USER_AGENT = 
        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
