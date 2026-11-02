package com.example.spotifywrapper

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.spotifywrapper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SPOTIFY_URL = "https://open.spotify.com"
        private const val STATE_WEBVIEW = "webview_state"

        // Desktop UA: Spotify Web Player rifiuta/funziona male con UA mobili.
        // (Il formato Chrome desktop è richiesto da "browser non supportato".)
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private lateinit var binding: ActivityMainBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AdBlocker.init(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = DESKTOP_UA
        }

        binding.webView.webViewClient = SpotifyWebViewClient(
            context = this,
            onProgress = { progress ->
                runOnUiThread { binding.progressBar.progress = progress }
            },
            onLoadingChanged = { isLoading ->
                runOnUiThread {
                    binding.progressBar.visibility =
                        if (isLoading) View.VISIBLE else View.GONE
                }
            }
        )

        binding.webView.webChromeClient = object : WebChromeClient() {

            // Supporto DRM/Widevine: senza questo, Spotify mostra
            // "La riproduzione di contenuti protetti non è abilitata."
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                        request.grant(request.resources)
                    } else {
                        // Microfono, fotocamera, ecc.: negati
                        request.deny()
                    }
                }
            }

            override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        // Tasto indietro: se la WebView puo' tornare indietro lo fa, altrimenti chiude l'app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState != null) {
            savedInstanceState.getBundle(STATE_WEBVIEW)?.let {
                binding.webView.restoreState(it)
            }
        } else {
            binding.webView.loadUrl(SPOTIFY_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val webState = Bundle()
        binding.webView.saveState(webState)
        outState.putBundle(STATE_WEBVIEW, webState)
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
