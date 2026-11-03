package com.example.spotifywrapper

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
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
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
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

    /** true solo se l'utente ha fermato la musica esplicitamente (notifica/indietro). */
    @Volatile
    private var userPausedPlayback = false

    /** true se la WebView era in riproduzione quando siamo andati in background. */
    private var wasPlayingBeforePause = false

    /** Ultimo stato play/pausa riportato dal bridge JS (vedi AndroidMediaBridge). */
    private var lastWebPlaying = false

    @Volatile
    private var webStateListener: ((Boolean) -> Unit)? = null

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

            // CLIENT HINTS: il solo User-Agent non basta. navigator.userAgentData
            // (Chrome 90+) riporta brand/piattaforma REALI (Android/mobile) e
            // Spotify usa quell'oggetto per decidere layout e compatibilita'.
            // WebSettingsCompat riscrive i Client Hints lato Chromium cosi' la
            // fingerprint coincide con Chrome desktop (mobile=false, Windows).
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                val metadata = UserAgentMetadata.Builder()
                    .setBrand("\"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\", \"Not?A_Brand\";v=\"24\"")
                    .setFullVersion("120.0.6099.230")
                    .setMajorVersion("120")
                    .setMobile(false)              // IMPORTANTISSIMO: NON mobile
                    .setPlatform("Windows")
                    .setPlatformVersion("15.0.0")
                    .setArchitecture("x86")
                    .setModel("")
                    .build()
                WebSettingsCompat.setUserAgentMetadata(this, metadata)
            }
        }

        // FIX TASTIERA: NON disabilitare mai isFocusable/isFocusableInTouchMode.
        // Erano stati messi a false per la reattivita'/tasti multimediali, ma una
        // WebView non focusabile non puo' dare il focus ai campi <input> del DOM:
        // Android non mostra piu' la IMM (login/ricerca impossibili). La reattivita'
        // e' ormai garantita da touch-action:manipulation + viewport maximum-scale=1
        // (inject.js) e dal setSupportZoom(false) sopra; i tasti multimediali sono
        // gestiti dalla MediaSessionCompat nel servizio, non dal focus della view.
        binding.webView.isFocusable = true
        binding.webView.isFocusableInTouchMode = true
        binding.webView.setBackgroundColor(Color.TRANSPARENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.webView.overScrollMode = View.OVER_SCROLL_NEVER
        }

        // NIENTE setOnTouchListener qui: in Android 10+ il listener si
        // sovrappone alla catena di dispatch della WebView e causa tap
        // "persi" o con doppio tocco. Lo zoom e' gia' impedito da
        // setSupportZoom(false) + touch-action:manipulation + maximum-scale=1
        // (viewport iniettato), che eliminano anche il ritardo dei 300ms.

        // FIX TASTIERA: la WebView deve essere il root focus della finestra,
        // altrimenti il focus del DOM non "propaga" alla IMM e la tastiera
        // non appare al primo tap. requestFocusFromTouch() marca la richiesta
        // come originata dal tocco (comportamento identico a Chrome).
        binding.webView.requestFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.webView.defaultFocusHighlightEnabled = false
        }

        // Bridge JS -> notifica media nativa (window.AndroidMediaBridge)
        binding.webView.addJavascriptInterface(
            AndroidMediaBridge(this, ::onWebPlaybackState),
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

            // FIX TASTIERA: quando un campo del DOM chiede il focus, la WebView
            // deve essere lei a detenerlo perche' la IMM si apra. Con layout
            // full-screen e nessun EditText nativo, showSoftInput() da solo non
            // basta se la view non e' gia' focalizzata: forzarlo qui rende la
            // tastiera affidabile al primo tap su login/ricerca.
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
                binding.webView.requestFocus()
            }

            override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        // Tasto indietro: PRIMA chiude la tastiera (se aperta), POI la cronologia
        // della WebView, infine doppia pressione con Toast per uscire.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hideKeyboardIfOpen()) return
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

    /**
     * Chiude la tastiera se visibile e toglie il focus al campo del DOM.
     * Ritorna true se ha gestito lei la pressione (niente goBack in tal caso).
     * Detection: il focus bridge JS (inject.js -> AndroidMediaBridge.onFocusChanged)
     * dice se un input web e' attivo; la geometria del layout (adjustResize)
     * conferma che la IMM e' davvero mostrata.
     */
    private fun hideKeyboardIfOpen(): Boolean {
        if (!domActiveElementIsInput) return false
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        binding.webView.evaluateJavascript(
            "try{document.activeElement&&document.activeElement.blur();}catch(e){}", null
        )
        domActiveElementIsInput = false
        return true
    }

    /** Aggiornato dall'hook JS (vedi inject.js): true se un campo DOM ha il focus. */
    @Volatile
    private var domActiveElementIsInput = false

    /** Chiamata dal JavascriptInterface AndroidMediaBridge.onFocusChanged. */
    fun onDomFocusChanged(isInput: Boolean) {
        runOnUiThread { domActiveElementIsInput = isInput }
    }

    /** Doppia pressione di Indietro per uscire: Toast + finestra di 2 secondi. */
    private var lastBackExitTime = 0L
    private fun handleExitBack() {
        val now = System.currentTimeMillis()
        if (now - lastBackExitTime < 2000L) {
            // In background la WebView non viene sospesa (serve all'audio):
            // se l'utente esce "davvero", fermiamo anche la riproduzione web.
            if (lastWebPlaying) {
                binding.webView.evaluateJavascript(
                    "window.__spotifyWrapperCmd && window.__spotifyWrapperCmd('pause');", null
                )
            }
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
        // Il servizio segnala al bridge quando la pausa e' stata richiesta
        // dall'utente (non dal web): autorizza l'onPause() "selettivo" e
        // disattiva il keep-alive audio iniettato (flag JS condiviso).
        MediaPlaybackService.onUserPaused = {
            userPausedPlayback = true
            binding.webView.evaluateJavascript(
                "window.__spotifyWrapperUserPaused = true;", null
            )
        }
        // Pausa NON utente (es. resume da play su UI web): riattiva il keep-alive.
        MediaPlaybackService.onUserResumed = {
            userPausedPlayback = false
            binding.webView.evaluateJavascript(
                "window.__spotifyWrapperUserPaused = false;", null
            )
        }
        // Comando arrivato mentre l'activity era distrutta (wake from notification):
        // viene eseguito ora che la WebView e' di nuovo raggiungibile.
        MediaPlaybackService.pendingCommand?.let { cmd ->
            MediaPlaybackService.pendingCommand = null
            binding.webView.post {
                binding.webView.evaluateJavascript(
                    "window.__spotifyWrapperCmd && window.__spotifyWrapperCmd('$cmd');", null
                )
            }
        }
    }

    /** Interroga lo stato reale del player web (callback sul main thread). */
    private fun queryWebPlaying(callback: (Boolean) -> Unit) {
        val script = "(function(){var a=document.querySelector('audio');" +
            "return JSON.stringify(!!(a && !a.paused && !a.ended));})()"
        binding.webView.evaluateJavascript(script) { result ->
            // evaluateJavascript restituisce una stringa JSON gia' quotata: es. "true".
            // toBoolean() e' primitivo Boolean, ma su un receiver nullable (result
            // e' String?) il risultato e' Boolean?: costringiamo il tipo a Boolean.
            val playing: Boolean = result?.trim('"', ' ')?.toBoolean() ?: false
            callback(playing)
        }
    }

    /** Ultimo stato play/pausa noto alla pagina (per l'onPause() selettivo). */
    fun onWebPlaybackState(isPlaying: Boolean) {
        runOnUiThread {
            lastWebPlaying = isPlaying
            // Se onPause attende ancora la risposta di queryWebPlaying, accontentalo.
            webStateListener?.invoke(isPlaying)
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
        MediaPlaybackService.mainActivityAlive = true
        askNotificationPermissionIfNeeded()
        bindMediaService()
        // Se la pagina e' stata caricata mentre l'app era in background, il
        // bridge JS ha bisogno del flag di visibilita' aggiornato (vedi spoof).
        binding.webView.evaluateJavascript(
            "window.__spotifyWrapperVisibility && window.__spotifyWrapperVisibility(true);", null
        )
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
        // Se l'utente ha fermato la musica dalla notifica e poi ha lasciato
        // l'app, zittisce anche il web player: evita un audio "fantasma" senza
        // sessione/notifica attiva.
        if (userPausedPlayback && lastWebPlaying) {
            MediaPlaybackService.pauseWebPlayback(binding.webView)
        }
        // La WebView passa a "window nascosta": la pagina lo percepisce e
        // Spotify mette in pausa + risulterebbe offline dal PC. Il bridge JS
        // fa credere alla pagina di essere sempre in primo piano (vedi inject.js).
        binding.webView.evaluateJavascript(
            "window.__spotifyWrapperVisibility && window.__spotifyWrapperVisibility(false);", null
        )
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
        // NON chiamare mai webView.onPause() in modo incondizionato: congelerebbe
        // timer/audio e la musica si fermerebbe in background. Lo sospendiamo
        // solo se l'utente ha premuto Pausa (notifica o UI): in tal caso il
        // webplayer e' gia' fermo, quindi congelare i timer fa risparmiare CPU.
        if (!userPausedPlayback) return super.onPause()

        val done = { playing: Boolean ->
            wasPlayingBeforePause = playing
            // Ricontrollo: nel frattempo l'activity potrebbe essere tornata avanti.
            if (!playing && userPausedPlayback && !isFinishing) {
                binding.webView.onPause()
            }
        }
        webStateListener = done
        queryWebPlaying(done)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        webStateListener = null
        // Se eravamo usciti per pausa utente, al rientro la WebView va riattivata;
        // lo stato play/pause del web player resta quello scelto dall'utente.
        userPausedPlayback = false
    }

    override fun onDestroy() {
        MediaPlaybackService.mainActivityAlive = false
        MediaPlaybackService.dispatchToWebView = null
        MediaPlaybackService.onUserPaused = null
        MediaPlaybackService.onUserResumed = null
        unbindMediaService()
        AndroidMediaBridge.resetServiceFlag()
        // Stacco la WebView dal view tree PRIMA di destroy(): evita il leak
        // del renderer Chromium (mantiene vivo il processore audio anche
        // dopo la distruzione dell'activity, con effetti su sessione/PC).
        (binding.webView.parent as? android.view.ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }
}
