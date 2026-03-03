package eu.kanade.tachiyomi.animeextension.en.hanime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

object WebViewExtractor {

    private const val TIMEOUT_MS = 15000L

    suspend fun extractAuthTokens(context: Context, videoSlug: String): Pair<String, Long> {
        return withTimeout(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context)
                val mainHandler = Handler(Looper.getMainLooper())

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebView.LOAD_DEFAULT
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url?.contains("/videos/hentai/") == true) {
                            mainHandler.postDelayed({
                                webView.evaluateJavascript(
                                    """
                                    (function() {
                                        return {
                                            signature: window.ssignature || '',
                                            timestamp: window.stime || 0
                                        };
                                    })();
                                    """.trimIndent(),
                                ) { result ->
                                    try {
                                        val json = org.json.JSONObject(result)
                                        val signature = json.optString("signature", "")
                                        val timestamp = json.optLong("timestamp", 0L)

                                        if (signature.isNotEmpty() && timestamp > 0L) {
                                            continuation.resume(Pair(signature, timestamp))
                                        } else {
                                            continuation.resume(Pair("", 0L))
                                        }
                                    } catch (e: Exception) {
                                        continuation.resume(Pair("", 0L))
                                    } finally {
                                        mainHandler.post { webView.destroy() }
                                    }
                                }
                            }, 3000)
                        }
                    }
                }

                mainHandler.post {
                    webView.loadUrl("https://hanime.tv/videos/hentai/$videoSlug")
                }
            }
        }
    }
}
