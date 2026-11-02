package com.example.spotifywrapper

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
    private var mediaService: MediaPlaybackService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mediaService = (service as? MediaPlaybackService.LocalBinder)?.service
            registerMediaDispatcher()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaService = null
        }
    }

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

        // Bridge JS -> notifica media nativa (window.AndroidMediaBridge)
        binding.webView.addJavascriptInterface(
            AndroidMediaBridge(this),
            AndroidMediaBridge.INTERFACE_NAME
        )

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

    /** I pulsanti della notifica finiscono qui: esegue il JS nella WebView. */
    private fun registerMediaDispatcher() {
        MediaPlaybackService.dispatchToWebView = { js ->
            runOnUiThread { binding.webView?.evaluateJavascript(js, null) }
        }
    }

    private fun bindMediaService() {
        if (serviceBound) return
        val intent = Intent(this, MediaPlaybackService::class.java)
        // BIND_AUTO_CREATE evita che il servizio venga distrutto quando l'activity
        // si disconnette mentre la riproduzione e' ancora attiva.
        serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        // Il servizio foreground viene avviato dal bridge alla prima riproduzione:
        // su Android 12+ un FGS mediaPlayback non puo' partire "a freddo"
        // quando l'app e' gia' in primo piano senza audio attivo.
    }

    private fun unbindMediaService() {
        if (serviceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {
            }
            serviceBound = false
        }
        mediaService = null
    }

    override fun onStart() {
        super.onStart()
        bindMediaService()
        // Chiedi il permesso notifiche su Android 13+ al primo avvio
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ).let { granted ->
                if (granted != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        AndroidMediaBridge.REQ_NOTIF_PERMISSION
                    )
                }
            }
        }
    }

    override fun onStop() {
        unbindMediaService()
        super.onStop()
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
        MediaPlaybackService.dispatchToWebView = null
        unbindMediaService()
        AndroidMediaBridge.resetServiceFlag()
        binding.webView.destroy()
        super.onDestroy()
    }
}
