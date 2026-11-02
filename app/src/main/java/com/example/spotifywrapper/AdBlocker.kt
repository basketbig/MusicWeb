package com.example.spotifywrapper

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Singleton: carica assets/ads_blocklist.txt all'avvio e risponde a "questa URL va bloccata?".
 */
object AdBlocker {

    private const val ASSET_FILE = "ads_blocklist.txt"
    private const val TAG = "AdBlocker"

    // Host esatti (lookup O(1))
    private val exactHosts = HashSet<String>()

    // Wildcard "*.esempio.com" -> matcho il suffisso "esempio.com" e i suoi sottodomini
    private val wildcardSuffixes = ArrayList<String>()

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadBlocklist(context)
            loaded = true
            Log.d(TAG, "Blocklist caricata: ${exactHosts.size} domini esatti, " +
                    "${wildcardSuffixes.size} wildcard")
        }
    }

    private fun loadBlocklist(context: Context) {
        try {
            context.assets.open(ASSET_FILE).use { input ->
                BufferedReader(InputStreamReader(input)).useLines { lines ->
                    lines.forEach { rawLine ->
                        val entry = rawLine.trim().lowercase()
                        when {
                            entry.isEmpty() -> return@forEach            // riga vuota
                            entry.startsWith("#") -> return@forEach      // commento
                            entry.contains("*") -> parseWildcard(entry)
                            else -> exactHosts.add(normalizeDomain(entry))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile leggere $ASSET_FILE", e)
        }
    }

    private fun parseWildcard(entry: String) {
        // Supportata solo la forma "*.esempio.com": le altre entry con "*"
        // verrebbero interpretate male, quindi si scartano esplicitamente.
        if (entry.startsWith("*.") && !entry.substring(2).contains('*')) {
            wildcardSuffixes.add(entry.removePrefix("*."))
        } else {
            Log.w(TAG, "Entry blocklist scartata (formato non supportato): $entry")
        }
    }

    private fun normalizeDomain(domain: String): String =
        domain.removePrefix(".").removePrefix("www.").trim('/')

    /** True se l'host dell'URL è presente in blocklist. */
    fun isBlocked(url: String): Boolean {
        if (!loaded) return false
        val host = url.toUri().host?.lowercase() ?: return false
        return matches(host)
    }

    private fun matches(host: String): Boolean {
        if (exactHosts.contains(host)) return true
        // www.foo.com deve matchare il blocco di foo.com
        val stripped = if (host.startsWith("www.")) host.substring(4) else host
        if (exactHosts.contains(stripped)) return true

        for (suffix in wildcardSuffixes) {
            if (host == suffix || host.endsWith(".$suffix")) return true
        }
        return false
    }
}
