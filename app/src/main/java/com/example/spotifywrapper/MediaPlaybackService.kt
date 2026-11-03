package com.example.spotifywrapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Foreground service che espone la riproduzione della WebView come sessione
 * media nativa: notifica di controllo (Play/Pausa, Prev, Next), lock screen
 * e integrazione con cuffie/BT.
 *
 * I dati arrivano dalla pagina web tramite il bridge JS "AndroidMediaBridge"
 * (vedi MainActivity); i comandi della notifica tornano alla pagina tramite
 * [dispatchToWebView], callback registrato da MainActivity.
 */
class MediaPlaybackService : Service() {

    inner class LocalBinder : Binder() {
        val service: MediaPlaybackService get() = this@MediaPlaybackService
    }

    companion object {
        // Canale condiviso: MainActivity lo crea PRIMA di ogni startForegroundService,
        // il servizio lo ricrea in onCreate (idempotente).
        const val CHANNEL_ID = "media_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.example.spotifywrapper.PLAY_PAUSE"
        const val ACTION_PREV = "com.example.spotifywrapper.PREV"
        const val ACTION_NEXT = "com.example.spotifywrapper.NEXT"
        const val ACTION_STOP = "com.example.spotifywrapper.STOP"

        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_ARTIST = "extra_artist"
        private const val EXTRA_IS_PLAYING = "extra_is_playing"

        /** Callback verso la WebView, registrato da MainActivity. */
        @Volatile
        var dispatchToWebView: ((js: String) -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // es. BackgroundServiceStartNotAllowedException su Android 12+
                Log.w("MediaPlaybackService", "start failed", e)
            }
        }

        /** Arresta il servizio e rimuove la notifica. */
        fun stop(context: Context) {
            context.stopService(Intent(context, MediaPlaybackService::class.java))
        }

        /**
         * PendingIntent del pulsante "Stop": il servizio e' exported=false, quindi
         * il click porta alla MainActivity (onNewIntent) che invoca MediaPlaybackService.stop().
         */
        fun stopPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).setAction(ACTION_STOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getActivity(context, ACTION_STOP.hashCode(), intent, flags)
        }

        /** Chiamato dal bridge JS quando la pagina cambia stato riproducibile. */
        fun update(
            context: Context,
            title: String,
            artist: String,
            album: String,
            artwork: Bitmap?,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long
        ) {
            instance?.let { svc ->
                svc.handleUpdate(title, artist, album, artwork, isPlaying, positionMs, durationMs)
                return
            }
            // Il servizio non è ancora attivo: lo avviamo e passiamo i dati via Intent
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w("MediaPlaybackService", "start failed", e)
            }
        }

        @Volatile
        private var instance: MediaPlaybackService? = null

        /** Istanza viva del servizio (per caricamenti copertina dal bridge). */
        fun peekInstance(): MediaPlaybackService? = instance
    }

    private var lastNotifUpdate = 0L
    private var notificationShown = false

    private lateinit var mediaSession: MediaSessionCompat
    private var artworkLoader: ArtworkLoader? = null
    private var binder: LocalBinder? = null

    private var currentTitle = ""
    private var currentArtist = ""
    private var currentlyPlaying = false

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1) Il canale DEVE esistere prima di qualsiasi startForeground().
        createChannel()

        mediaSession = MediaSessionCompat(this, "SpotifyWrapperSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = sendCommand("play")
                override fun onPause() = sendCommand("pause")
                override fun onSkipToNext() = sendCommand("next")
                override fun onSkipToPrevious() = sendCommand("prev")
                override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                    if (action != null) sendCommand(action)
                }
                override fun onStop() {
                    stopSelf()
                }
            })
            isActive = true
        }

        artworkLoader = ArtworkLoader(this)

        // 2) Entro 5 secondi dall'avvio del servizio bisogna chiamare
        //    startForeground(), altrimenti ANR/crash.
        startForegroundCompat()
    }

    private fun startForegroundCompat() {
        // Canale gia' creato in onCreate: qui garantiamo comunque (idempotente).
        createChannel()

        val notification = buildPlaceholderNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: dichiara il tipo mediaPlayback (obbligatorio su 14).
                // ServiceCompat gestisce le differenze tra release della piattaforma.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                // API 26-28: FOREGROUND_SERVICE e' richiesto ma il tipo FGS no.
                // API < 26: la chiamata e' sempre lecita.
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // Permesso FOREGROUND_SERVICE(_MEDIA_PLAYBACK) non riconosciuto dal
            // sistema (es. APK installato con un manifest vecchio): NON crashare.
            Log.e("MediaPlaybackService", "startForeground negato: chiudo il servizio", e)
            try {
                stopSelf()
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            // Es. MissingForegroundNotificationPermissionException su Android 13+
            // se il canale/notifica non e' valido: chiudi in modo pulito.
            Log.e("MediaPlaybackService", "impossibile andare in foreground", e)
            try {
                stopSelf()
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Se qualcuno ci ha ri-avviati con startForegroundService (es. bridge JS),
        // il sistema pretende una chiamata a startForeground() ENTRO 5 secondi,
        // anche se il servizio era gia' in foreground: la ripetiamo (idempotente).
        if (intent != null && intent.action == null) {
            startForegroundCompat()
        }
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_PREV -> sendCommand("prev")
            ACTION_NEXT -> sendCommand("next")
            else -> {
                // Avvio "pulito" o con dati iniziali dall'Intent (servizio non attivo)
                val title = intent?.getStringExtra(EXTRA_TITLE)
                val artist = intent?.getStringExtra(EXTRA_ARTIST)
                val playing = intent?.getBooleanExtra(EXTRA_IS_PLAYING, false) ?: false
                if (title != null || artist != null) {
                    handleUpdate(title ?: "", artist ?: "", "", null, playing, 0L, 0L)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(webIntent: Intent?): IBinder {
        // Legame stabile: stesso binder per tutta la vita del servizio, cosi'
        // MainActivity puo' collegarsi/scollegarsi senza ri-avviarne il ciclo foreground.
        return binder ?: LocalBinder().also { binder = it }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // L'utente ha rimosso il task dalle app recenti: stop pulito della
        // riproduzione nativa (la WebView segue il ciclo dell'activity).
        AndroidMediaBridge.resetServiceFlag()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        dispatchToWebView = null
        artworkLoader?.shutdown()
        mediaSession.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Aggiornamento stato dalla pagina web
    // ------------------------------------------------------------------

    private fun handleUpdate(
        title: String,
        artist: String,
        album: String,
        artwork: Bitmap?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        val changed = title != currentTitle || artist != currentArtist
        currentTitle = title
        currentArtist = artist
        currentlyPlaying = isPlaying

        val mdBuilder = MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            if (album.isNotBlank()) putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            if (durationMs > 0) putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        }

        // Copertina: la nuova vince; altrimenti, se il brano non e' cambiato,
        // conserva quella gia' in sessione (gli heartbeat di posizione portano
        // artwork=null e non devono cancellare l'immagine).
        // Nota: METADATA_KEY_IMAGE_PREFERRED e' un int di tipo, non un boolean,
        // e getBoolean() non esiste su BaseBundle: confrontiamo direttamente i bitmap.
        val newArt = artwork
            ?: if (!changed) {
                mediaSession.controller.metadata
                    ?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            } else {
                null
            }
        if (newArt != null) {
            mdBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, newArt)
            mdBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, newArt)
        }
        mediaSession.setMetadata(mdBuilder.build())

        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, positionMs, if (isPlaying) 1f else 0f)
                .build()
        )

        // Throttle: gli heartbeat del bridge JS arrivano ogni pochi secondi;
        // aggiornare la notifica a ogni battito causa flickering inutile.
        val now = SystemClock.elapsedRealtime()
        if (!notificationShown || now - lastNotifUpdate > 2000L) {
            lastNotifUpdate = now
            postNotification()
        }
    }

    /** notify() sicuro: non crasha se POST_NOTIFICATIONS e' stato negato (Android 13+). */
    private fun postNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            notificationShown = true
        } catch (e: SecurityException) {
            Log.w("MediaPlaybackService", "notifica negata: permesso POST_NOTIFICATIONS mancante", e)
        }
    }

    /** Carica la copertina in modo asincrono e aggiorna metadata + notifica. */
    fun loadArtwork(url: String) {
        val loader = artworkLoader ?: return
        val expectedTitle = currentTitle
        loader.load(url) { bmp ->
            if (bmp == null || currentTitle != expectedTitle) return@load
            val mdBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bmp)
            mediaSession.setMetadata(mdBuilder.build())
            postNotification()
        }
    }

    // ------------------------------------------------------------------
    // Comandi notifica -> WebView
    // ------------------------------------------------------------------

    private fun sendCommand(command: String) {
        val js = "window.__spotifyWrapperCmd && window.__spotifyWrapperCmd('$command');"
        dispatchToWebView?.invoke(js)
    }

    private fun togglePlayPause() {
        sendCommand(if (currentlyPlaying) "pause" else "play")
    }

    // ------------------------------------------------------------------
    // Notifica
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** Notifica base usata per mettere il servizio in foreground. */
    private fun buildPlaceholderNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notif_playing))
            .setContentText(currentArtist)
            .setContentIntent(sessionActivityIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun sessionActivityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun mediaActionIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).setAction(action)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun buildNotification(): Notification {
        val playPauseAction = if (currentlyPlaying) {
            Triple(R.drawable.ic_pause, getString(R.string.notif_pause), ACTION_PLAY_PAUSE)
        } else {
            Triple(R.drawable.ic_play, getString(R.string.notif_play), ACTION_PLAY_PAUSE)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(currentTitle.ifBlank { getString(R.string.app_name) })
            .setContentText(currentArtist)
            .setContentIntent(sessionActivityIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(R.drawable.ic_skip_previous, getString(R.string.notif_prev), mediaActionIntent(ACTION_PREV))
            .addAction(playPauseAction.first, playPauseAction.second, mediaActionIntent(playPauseAction.third))
            .addAction(R.drawable.ic_skip_next, getString(R.string.notif_next), mediaActionIntent(ACTION_NEXT))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_stop),
                MediaPlaybackService.stopPendingIntent(this)
            )
        // Copertina come large icon, se disponibile in sessione
        mediaSession.controller.metadata
            ?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            ?.let { builder.setLargeIcon(it) }

        builder.setStyle(
            MediaNotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(mediaSession.sessionToken)
        )

        return builder.build()
    }
}
