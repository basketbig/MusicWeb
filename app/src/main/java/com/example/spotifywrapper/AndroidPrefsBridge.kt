package com.example.spotifywrapper

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * Bridge nativo -> pagina per le preferenze di personalizzazione.
 * Esposto come window.AndroidPrefsBridge: inject.js chiama getPrefs()
 * a ogni ri-applicazione degli stili, quindi i temi/flag cambiano a
 * caldo senza ricaricare la WebView.
 */
class AndroidPrefsBridge(private val context: Context) {

    companion object {
        const val INTERFACE_NAME = "AndroidPrefsBridge"
    }

    @JavascriptInterface
    fun getPrefs(): String = Prefs.getJson(context)
}
