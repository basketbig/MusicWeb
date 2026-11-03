package com.example.spotifywrapper

import android.Manifest
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.spotifywrapper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SPOTIFY_URL = "https://open.spotify.com"
        private const val STATE_WEBVIEW = "webview_state"
        private const val TAG = "MainActivity"

        // Desktop UA: Spotify Web Player rifiuta/funziona male con UA mobili.
        // (Il formato Chrome desktop è richiesto da "browser non supportato".)
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private lateinit var binding: ActivityMainBinding
    private var mediaService: MediaPlaybackService? = null
    private var serviceBound = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Su Android 14 un FGS mediaPlayback SENZA notifica visibile puo'
            // essere bloccato dal sistema: niente sessione media se negato.
            android.util.Log.w(TAG, "POST_NOTIFICATIONS negato: controls non disponibili")
        }
    }

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

        // Il canale di notifica DEVE esistere prima che il bridge chiami
        // startForegroundService (es. avvio a freddo alla prima riproduzione).
        createChannel()

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

            // UI app-like: lo zoom (pizzico/doppiotap) confligge con il layout
            // desktop forzato; inject.js imposta comunque maximum-scale=1.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        // Disabilita lo zoom da gestore touch a livello di WebView (pinch + double-tap).
        binding.webView.setOnTouchListener { v, _ -> v.performClick(); false }

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

        // Tasto indietro: WebView se puo' tornare indietro; altrimenti doppia
        // pressione con Toast per uscire (anti-uscita accidentale).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    handleExitBack()
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

    /** Doppia pressione di Indietro per uscire: Toast + finestra di 2 secondi. */
    private var lastBackExitTime = 0L
    private fun handleExitBack() {
        val now = System.currentTimeMillis()
        if (now - lastBackExitTime < 2000L) {
            finish()
        } else {
            lastBackExitTime = now
            android.widget.Toast.makeText(
                this, "Premi di nuovo per uscire", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Suppress("DEPRECATION") // firma nullable: non-nullable solo da appcompat 1.7 (compileSdk 35)
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Il pulsante "Stop" della notifica punta a questa activity perche' il
        // servizio e' exported=false e non puo' ricevere PendingIntent getService.
        if (intent?.action == MediaPlaybackService.ACTION_STOP) {
            AndroidMediaBridge.resetServiceFlag()
            MediaPlaybackService.stop(this)
            intent.action = null
        }
    }

    /** I pulsanti della notifica finiscono qui: esegue il JS nella WebView. */
    private fun registerMediaDispatcher() {
        MediaPlaybackService.dispatchToWebView = { js ->
            runOnUiThread { binding.webView.evaluateJavascript(js, null) }
        }
    }

    /** Canale di notifica: anticipato in onCreate per evitare crash su avvio a freddo. */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                MediaPlaybackService.CHANNEL_ID,
                getString(R.string.notif_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(android.app.NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun bindMediaService() {
        val intent = Intent(this, MediaPlaybackService::class.java)
        if (!serviceBound) {
            // BIND_AUTO_CREATE evita che il servizio venga distrutto quando l'activity
            // si disconnette mentre la riproduzione e' ancora attiva.
            serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        // Ri-allega sempre il dispatcher: dopo onStop/onDestroy il campo statico
        // viene azzerato e i pulsanti della notifica resterebbero muti.
        registerMediaDispatcher()
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
        askNotificationPermissionIfNeeded()
        bindMediaService()
    }

    /** POST_NOTIFICATIONS e' runtime su Android 13+: senza, niente notifica media. */
    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Start/stop espliciti del foreground service (uso manuale/debug). */
    @Suppress("unused")
    private fun startMediaService() {
        createChannel()
        ContextCompat.startForegroundService(
            this, Intent(this, MediaPlaybackService::class.java)
        )
    }

    @Suppress("unused")
    private fun stopMediaService() {
        AndroidMediaBridge.resetServiceFlag()
        MediaPlaybackService.stop(this)
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
