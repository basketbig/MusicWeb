package com.example.spotifywrapper

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Bridge JS -> Android esposto nella WebView come "AndroidMediaBridge".
 * media_bridge.js lo usa per inviare titolo/artista/stato/copertina alla
 * notifica nativa; i pulsanti della notifica tornano alla pagina tramite
 * MediaPlaybackService.dispatchToWebView (vedi MainActivity).
 */
class AndroidMediaBridge(
    private val context: Context,
    /** Callback opzionale verso l'activity per lo stato play/pausa corrente. */
    private val onStateChanged: ((Boolean) -> Unit)? = null
) {

    @JavascriptInterface
    fun updatePlaybackState(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val obj = JSONObject(json)
            val title = obj.optString("title", "")
            val artist = obj.optString("artist", "")
            val album = obj.optString("album", "")
            val isPlaying = obj.optBoolean("isPlaying", false)
            val positionMs = obj.optLong("positionMs", 0L)
            val durationMs = obj.optLong("durationMs", 0L)
            val artworkUrl = obj.optString("artworkUrl", "")

            Log.d(TAG, "stato web: playing=$isPlaying \"$title\" - \"$artist\"")

            // Lo stato play/pausa corrente serve alla MainActivity per decidere
            // se sospendere la WebView in onPause() senza zittire l'audio.
            onStateChanged?.invoke(isPlaying)

            // Avvia il servizio foreground solo quando c'e' riproduzione attiva:
            // su Android 12+ un FGS di tipo mediaPlayback non puo' partire
            // "a freddo" dal bridge JS senza una sessione audio in corso.
            if (isPlaying && !serviceStarted) {
                serviceStarted = true
                MediaPlaybackService.start(context)
            }
            if (!serviceStarted) return // niente sessione attiva: non creare notifiche a vuoto
            requestNotificationPermissionIfNeeded()

            // Se il servizio non e' ancora nato (startForegroundService e' asincrona),
            // salta questo battito: il prossimo heartbeat lo intercettera' vivo.
            if (isPlaying && MediaPlaybackService.peekInstance() == null) return

            MediaPlaybackService.update(
                context = context,
                title = title,
                artist = artist,
                album = album,
                artwork = null,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs
            )
            if (artworkUrl.isNotBlank()) {
                MediaPlaybackService.peekInstance()?.loadArtwork(artworkUrl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON stato non valido", e)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // POST_NOTIFICATIONS viene chiesto in MainActivity.onStart(): qui ci
        // limitiamo a verificare (registerForActivityResult non e' invocabile
        // fuori dal ciclo di creazione dell'activity).
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) Log.w(TAG, "notifiche negate: la sessione media restera' senza notifica")
    }

    companion object {
        private const val TAG = "AndroidMediaBridge"
        const val INTERFACE_NAME = "AndroidMediaBridge"
        @Volatile
        private var serviceStarted = false

        /** Reset del flag: chiamato da MainActivity in onDestroy. */
        fun resetServiceFlag() {
            serviceStarted = false
        }
    }
}
