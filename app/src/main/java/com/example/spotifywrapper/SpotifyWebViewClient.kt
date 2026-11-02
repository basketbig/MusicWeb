package com.example.spotifywrapper

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri

/**
 * WebViewClient per il player web Spotify:
 * - blocca le richieste verso i domini della blocklist ([AdBlocker]);
 * - apre i link esterni nel browser di sistema;
 * - gestisce la progress bar;
 * - inietta assets/inject.js a pagina caricata.
 */
class SpotifyWebViewClient(
    private val context: Context,
    private val onProgress: (Int) -> Unit,
    private val onLoadingChanged: (Boolean) -> Unit
) : WebViewClient() {

    companion object {
        // Host (o suffissi) considerati "Spotify": restano dentro la WebView
        private val SPOTIFY_HOST_SUFFIXES = listOf(
            "spotify.com",
            "spotify.link",
            "scdn.co",
            "map.fastly.net"
        )

        fun isSpotifyRelatedUrl(url: String): Boolean {
            val host = url.toUri().host?.lowercase() ?: return false
            return SPOTIFY_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (url != null && AdBlocker.isBlocked(url)) {
            // 204 No Content: risposta vuota e valida, il JS di pagina non va in errore
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                204,
                "No Content",
                mapOf("Access-Control-Allow-Origin" to "*"),
                null
            )
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        val scheme = request.url.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false

        // Solo i redirect "top-level" (non iframe/subresource) contano qui
        if (!request.isForMainFrame) return false

        if (isSpotifyRelatedUrl(url)) return false // resta nella WebView

        ExternalBrowser.open(context, url)
        return true
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        onLoadingChanged(true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onLoadingChanged(false)
        onProgress(100)
        JsInjector.getScript(context)?.let { script ->
            view?.evaluateJavascript(script, null)
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        // Spotify è una SPA: le transizioni interne non chiamano onPageFinished,
        // quindi re-iniettiamo lo script anti-ads dopo ogni cambio di rotta.
        JsInjector.getScript(context)?.let { script ->
            view?.postDelayed({ view.evaluateJavascript(script, null) }, 500)
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?
    ) {
        // Gli errori sulle sottorisorse bloccate/non critiche non devono rompere il player
        android.util.Log.w(
            "SpotifyWebViewClient",
            "load error ${request?.url}: ${error?.description}"
        )
    }
}

/** Apre gli URL non-Spotify nel browser di sistema. */
object ExternalBrowser {
    fun open(context: Context, url: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("ExternalBrowser", "Nessun browser per $url", e)
        }
    }
}
