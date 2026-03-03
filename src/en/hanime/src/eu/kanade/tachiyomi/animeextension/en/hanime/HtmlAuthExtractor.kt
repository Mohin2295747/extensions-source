package eu.kanade.tachiyomi.animeextension.en.hanime

import android.util.Base64
import java.util.regex.Pattern

object HtmlAuthExtractor {
    fun extractAuthTokens(html: String, videoId: String): Pair<String, Long> {
        val timestamp = extractTimestampFromNuxt(html)
        val signature = generateGuestSignature(timestamp, videoId)
        return Pair(signature, timestamp)
    }
    
    private fun extractTimestampFromNuxt(html: String): Long {
        val nuxtPattern = Pattern.compile("window\\.__NUXT__\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL)
        val nuxtMatcher = nuxtPattern.matcher(html)
        
        if (nuxtMatcher.find()) {
            val nuxtJson = nuxtMatcher.group(1)
            val stimePattern = Pattern.compile("\"stime\"\\s*:\\s*(\\d+)")
            val stimeMatcher = stimePattern.matcher(nuxtJson)
            
            if (stimeMatcher.find()) {
                return stimeMatcher.group(1).toLongOrNull() ?: 0L
            }
        }
        
        val directPattern = Pattern.compile("window\\.stime\\s*=\\s*(\\d+);")
        val directMatcher = directPattern.matcher(html)
        
        if (directMatcher.find()) {
            return directMatcher.group(1).toLongOrNull() ?: 0L
        }
        
        return 0L
    }
    
    private fun generateGuestSignature(timestamp: Long, videoId: String): String {
        val data = "$timestamp:guest:$videoId"
        return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
    }
}
