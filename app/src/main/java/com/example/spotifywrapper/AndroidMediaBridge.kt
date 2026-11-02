package com.example.spotifywrapper

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.app.ActivityCompat
import org.json.JSONObject

/**
 * Bridge JS -> Android esposto nella WebView come "AndroidMediaBridge".
 * media_bridge.js lo usa per inviare titolo/artista/stato/copertina alla
 * notifica nativa; i pulsanti della notifica tornano alla pagina tramite
 * MediaPlaybackService.dispatchToWebView (vedi MainActivity).
 */
class AndroidMediaBridge(private val context: Context) {

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

            // Avvia il servizio foreground solo quando c'e' riproduzione attiva:
            // su Android 12+ un FGS di tipo mediaPlayback non puo' partire
            // "a freddo" dal bridge JS senza una sessione audio in corso.
            if (isPlaying && !serviceStarted) {
                serviceStarted = true
                MediaPlaybackService.start(context)
            }
            if (!serviceStarted) return // niente sessione attiva: non creare notifiche a vuoto
            requestNotificationPermissionIfNeeded()

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
        if (notificationPermissionRequested) return
        notificationPermissionRequested = true
        val activity = context as? Activity ?: return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQ_NOTIF_PERMISSION
        )
    }

    companion object {
        private const val TAG = "AndroidMediaBridge"
        const val INTERFACE_NAME = "AndroidMediaBridge"
        const val REQ_NOTIF_PERMISSION = 42

        @Volatile
        private var serviceStarted = false
        private var notificationPermissionRequested = false

        /** Reset del flag: chiamato da MainActivity in onDestroy. */
        fun resetServiceFlag() {
            serviceStarted = false
        }
    }
}
