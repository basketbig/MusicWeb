# Spotify Wrapper (Android, uso personale)

Wrapper nativo Kotlin del player web di Spotify (`https://open.spotify.com`) con
blocco annunci integrato su due livelli:

1. **AdBlocker.kt** + `assets/ads_blocklist.txt` -> intercettazione a livello rete
   (`shouldInterceptRequest`): le richieste verso i domini in blocklist ricevono
   una risposta vuota HTTP 204.
2. **JsInjector.kt** + `assets/inject.js` -> rimozione CSS/DOM dei banner e degli
   slot "Advertisement" dentro la pagina (MutationObserver, gestisce anche le
   transizioni SPA).

## Struttura

```
app/src/main/
├── java/com/example/spotifywrapper/
│   ├── MainActivity.kt          # WebView full-screen + ProgressBar, UA desktop, back, stato
│   ├── SpotifyWebViewClient.kt  # shouldInterceptRequest, redirect browser esterni, injection
│   ├── AdBlocker.kt             # singleton: carica blocklist, isBlocked(url)
│   └── JsInjector.kt            # legge/caches inject.js dagli assets
├── assets/
│   ├── ads_blocklist.txt        # un dominio per riga, # = commento, *. wildcard
│   └── inject.js                # anti-ads DOM/CSS lato pagina
└── res/layout/activity_main.xml # WebView a tutto schermo + ProgressBar orizzontale in alto
```

## Configurazione richiesta (build.gradle.kts)

- minSdk 24, targetSdk/compileSdk 34, Kotlin DSL, AGP 8.x, solo AndroidX.

## Come aprire il progetto in Android Studio

1. **File > Open...** e seleziona la cartella radice del progetto (quella con
   `settings.gradle.kts`). Non usare "Import Project" sui vecchi formati: con
   Gradle Kotlin DSL basta Open.
2. Attendi il completamento della **sync Gradle** (icona elefante / "Sync Now").
   La prima volta scarica Gradle 8.5 + dipendenze AndroidX: serve internet.
3. Se la sync fallisce: **File > Sync Project with Gradle Files**, oppure
   **File > Invalidate Caches / Restart**.
4. Collega il **dispositivo fisico** via USB ed abilita **Opzioni sviluppatore >
   Debug USB** (Settings > Info telefono > tocca 7 volte "Numero build").
   IMPORTANTE: usa un dispositivo reale ed NON l'emulatore: sulla maggior parte
   degli emulatori x86 il DRM/audio di Spotify Web Player non funziona o è
   estremamente instabile, quindi non potresti verificare il risultato.
5. Seleziona il dispositivo nella toolbar e premi **Run > Run 'app'** (Shift+F10).
   Per firmare la release ad uso personale: **Build > Generate Signed Bundle/APK**
   oppure installa direttamente la variante debug sul tuo telefono.
6. Al primo avvio fai login nell'account Spotify dentro la WebView (i cookie
   restano salvati nel profilo WebView dell'app).

## Nota finale sui test

- Verifica il blocco ads con **chrome://inspect**: collega il telefono via USB,
  apri Chrome sul PC, vai su `chrome://inspect/#devices`, ispeziona la WebView.
  In Console vedrai i log `[AdBlocker]` di `inject.js` (elementi nascosti);
  in Network vedrai le risposte 204 ai domini bloccati.
- Test funzionale manuale rapido: riproduci una playlist con un account FREE:
  gli slot audio/video ads vengono bloccati dalla blocklist; se la musica non
  parte, commenta in `ads_blocklist.txt` gli ultimi domini "di confine"
  (gia' commentati nel file) e prova di nuovo.
- Le modifiche alla blocklist non richiedono ricompilazione del codice: basta
  editare `assets/ads_blocklist.txt` e reinstallare.

## Avvertenze

Solo per uso personale sull'account proprio. L'uso di wrapper/ad-block puo'
violare i ToS di Spotify: non ridistribuire l'app.
