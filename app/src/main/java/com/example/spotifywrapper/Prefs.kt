package com.example.spotifywrapper

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferenze di personalizzazione UI (temi + visibilita' elementi).
 * Unico punto di verita': le legge SettingsActivity (scrittura) e il
 * bridge JS iniettato nella WebView (lettura via getJson()).
 */
object Prefs {

    private const val FILE = "wrapper_prefs"

    const val KEY_THEME = "theme"
    const val KEY_HIDE_NOW_PLAYING_PANEL = "hide_now_playing_panel"
    const val KEY_HIDE_LEFT_SIDEBAR = "hide_left_sidebar"
    const val KEY_BIGGER_FONTS = "bigger_fonts"

    const val THEME_DEFAULT = "default"
    const val THEME_AMOLED = "amoled"
    const val THEME_DARK_VIOLET = "dark_violet"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun theme(context: Context): String =
        prefs(context).getString(KEY_THEME, THEME_DEFAULT) ?: THEME_DEFAULT

    fun hideNowPlayingPanel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_NOW_PLAYING_PANEL, false)

    fun hideLeftSidebar(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_LEFT_SIDEBAR, false)

    fun biggerFonts(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BIGGER_FONTS, false)

    /** JSON passato alla pagina: lo legge inject.js via AndroidPrefsBridge.getPrefs(). */
    fun getJson(context: Context): String =
        "{" +
            "\"theme\":\"" + theme(context) + "\"," +
            "\"hideNowPlaying\":" + hideNowPlayingPanel(context) + "," +
            "\"hideSidebar\":" + hideLeftSidebar(context) + "," +
            "\"biggerFonts\":" + biggerFonts(context) +
            "}"
}
