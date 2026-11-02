package com.example.spotifywrapper

import android.content.Context
import android.util.Log

/**
 * Legge gli script di assets (inject.js + media_bridge.js), li combina una
 * sola volta e li restituisce pronti per WebView.evaluateJavascript().
 */
object JsInjector {

    private const val TAG = "JsInjector"

    /** Ordine di iniezione: anti-ads prima, ponte media dopo. */
    private val ASSET_FILES = listOf("inject.js", "media_bridge.js")

    @Volatile
    private var cachedScript: String? = null

    /** Script combinati, pronti per WebView.evaluateJavascript(). */
    fun getScript(context: Context): String? {
        cachedScript?.let { return it }
        synchronized(this) {
            cachedScript?.let { return it }
            val sb = StringBuilder()
            for (name in ASSET_FILES) {
                try {
                    context.assets.open(name).bufferedReader().use { sb.append(it.readText()).append('\n') }
                } catch (e: Exception) {
                    Log.e(TAG, "Impossibile leggere $name", e)
                }
            }
            val script = sb.toString().ifBlank { null }
            cachedScript = script
            return script
        }
    }
}
