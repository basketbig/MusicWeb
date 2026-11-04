package com.example.spotifywrapper

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity

/**
 * Schermata minimale di personalizzazione (no design curato: servono solo
 * i controlli). Ogni modifica viene salvata in SharedPreferences e notificata
 * alla MainActivity, che ri-applica gli stili nella WebView senza ricaricare.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** Extra dell'Intent con cui MainActivity riapplica gli stili. */
        const val ACTION_PREFS_CHANGED = "com.example.spotifywrapper.PREFS_CHANGED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val themeDefault = findViewById<Button>(R.id.btnThemeDefault)
        val themeAmoled = findViewById<Button>(R.id.btnThemeAmoled)
        val themeViolet = findViewById<Button>(R.id.btnThemeViolet)
        val hideNowPlaying = findViewById<CheckBox>(R.id.chkHideNowPlaying)
        val hideSidebar = findViewById<CheckBox>(R.id.chkHideSidebar)
        val biggerFonts = findViewById<CheckBox>(R.id.chkBiggerFonts)

        // Stato iniziale da SharedPreferences
        when (Prefs.theme(this)) {
            Prefs.THEME_AMOLED -> themeAmoled.isSelected = true
            Prefs.THEME_DARK_VIOLET -> themeViolet.isSelected = true
            else -> themeDefault.isSelected = true
        }
        hideNowPlaying.isChecked = Prefs.hideNowPlayingPanel(this)
        hideSidebar.isChecked = Prefs.hideLeftSidebar(this)
        biggerFonts.isChecked = Prefs.biggerFonts(this)

        fun setTheme(name: String, selected: Button) {
            Prefs.prefs(this).edit().putString(Prefs.KEY_THEME, name).apply()
            listOf(themeDefault, themeAmoled, themeViolet).forEach { it.isSelected = false }
            selected.isSelected = true
            notifyChange()
        }
        themeDefault.setOnClickListener { setTheme(Prefs.THEME_DEFAULT, themeDefault) }
        themeAmoled.setOnClickListener { setTheme(Prefs.THEME_AMOLED, themeAmoled) }
        themeViolet.setOnClickListener { setTheme(Prefs.THEME_DARK_VIOLET, themeViolet) }

        hideNowPlaying.setOnCheckedChangeListener { _, checked ->
            Prefs.prefs(this).edit().putBoolean(Prefs.KEY_HIDE_NOW_PLAYING_PANEL, checked).apply()
            notifyChange()
        }
        hideSidebar.setOnCheckedChangeListener { _, checked ->
            Prefs.prefs(this).edit().putBoolean(Prefs.KEY_HIDE_LEFT_SIDEBAR, checked).apply()
            notifyChange()
        }
        biggerFonts.setOnCheckedChangeListener { _, checked ->
            Prefs.prefs(this).edit().putBoolean(Prefs.KEY_BIGGER_FONTS, checked).apply()
            notifyChange()
        }
    }

    /** Riporta il segnale alla MainActivity (singleTop) senza ricostruirla. */
    private fun notifyChange() {
        startActivity(
            android.content.Intent(this, MainActivity::class.java)
                .setAction(ACTION_PREFS_CHANGED)
                // NEW_TASK perche' partiamo da un'altra activity; REORDER_TO_FRONT
                // riporta in primo piano l'istanza ESISTENTE della MainActivity
                // (cosi' la WebView non si ricostruisce ne' ricarica). Senza,
                // con launchMode standard il sistema ne creerebbe una copia nuova.
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
        )
    }
}
