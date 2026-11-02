package com.example.spotifywrapper

import android.content.Context
import android.util.Log

/**
 * Legge assets/inject.js una sola volta e lo restituisce come stringa pronta
 * per WebView.evaluateJavascript().
 */
object JsInjector {

    private const val ASSET_FILE = "inject.js"
    private const val TAG = "JsInjector"

    @Volatile
    private var cachedScript: String? = null

    fun getScript(context: Context): String? {
        cachedScript?.let { return it }
        synchronized(this) {
            cachedScript?.let { return it }
            val script = try {
                context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Impossibile leggere $ASSET_FILE", e)
                null
            }
            cachedScript = script
            return script
        }
    }
}
