package com.example.spotifywrapper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Scarica le copertine degli album (URL i.scdn.co) su un thread di background
 * e le ridimensiona per la notifica. Nessun framework di rete esterno:
 * HttpURLConnection + executor, come da vincoli del progetto.
 */
class ArtworkLoader(context: Context) {

    private val cacheDir = context.cacheDir
    private val executor = Executors.newSingleThreadExecutor()

    fun shutdown() {
        executor.shutdownNow()
    }

    fun load(url: String, callback: (Bitmap?) -> Unit) {
        // Guard difensiva: mai far crashare il servizio per un URL malformato.
        try {
            if (!url.startsWith("http")) {
                callback(null)
                return
            }
            executor.execute {
                try {
                    val file = downloadToCache(url)
                    if (file != null) {
                        // Decodifica a dimensione ridotta: basta per la notifica
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, opts)
                        var sample = 1
                        while (opts.outWidth / sample > TARGET_PX * 2) sample *= 2
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                        val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                        handler.post { callback(bmp) }
                    } else {
                        handler.post { callback(null) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "artwork fallita per $url", e)
                    handler.post { callback(null) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "artwork: submit fallito", e)
            callback(null)
        }
    }

    private fun downloadToCache(url: String): java.io.File? {
        val key = url.hashCode().toString(16)
        val out = java.io.File(cacheDir, "art_$key.jpg")
        if (out.exists() && out.length() > 0) return out

        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out
            } else {
                out.delete()
                null
            }
        } catch (e: Exception) {
            out.delete()
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "ArtworkLoader"
        private const val TARGET_PX = 320
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    }
}
