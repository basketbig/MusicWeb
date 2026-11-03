/* ============================================================
 * media_bridge.js - Ponte tra il DOM di Spotify Web Player e la
 * notifica media nativa (AndroidMediaBridge -> MediaPlaybackService).
 *
 * 1) Legge titolo/artista/copertina/stato dal player web.
 * 2) Invia gli stati "significativi" al bridge Android.
 * 3) Riceve i comandi dalla notifica: window.__spotifyWrapperCmd(cmd).
 *    Prima prova l'API HTML5 <audio> (piu' affidabile del click sui
 *    bottoni, perche' non dipende dai data-testid che Spotify cambia);
 *    in caso di fallimento ripiega sui selettori del player.
 * ============================================================ */
(function () {
    'use strict';

    var TAG = '[MediaBridge]';

    if (window.__spotifyWrapperMediaBridge) {
        // Seconda iniezione (navigazione SPA): niente doppio observer, MA va
        // re-associato il listener play/pause sugli <audio>: il bridge nativo
        // AndroidMediaBridge e' stato ricreato con un nuovo oggetto Java e i
        // vecchi closure chiamerebbero un reference stale.
        rescanAudio(true);
        report(true);
        return;
    }
    window.__spotifyWrapperMediaBridge = true;

    function hasBridge() {
        return typeof AndroidMediaBridge !== 'undefined' && AndroidMediaBridge !== null;
    }

    // ------------------------------------------------------------
    // API pubbliche richieste dal servizio nativo / dalla pagina.
    // Il servizio richiama window.playPause()/nextTrack()/previousTrack()
    // come alias dei comandi interni (vedi __spotifyWrapperCmd).
    // ------------------------------------------------------------
    function exposeCommandApi() {
        window.playPause = function () { window.__spotifyWrapperCmd('play'); };
        window.pause = function () { window.__spotifyWrapperCmd('pause'); };
        window.nextTrack = function () { window.__spotifyWrapperCmd('next'); };
        window.previousTrack = function () { window.__spotifyWrapperCmd('prev'); };
    }

    // Bridge "AndroidBridge" alla lettera, come da specifica: wrapper sottile
    // sopra AndroidMediaBridge che accetta metadati come argomenti separati.
    window.AndroidBridge = {
        updateMetadata: function (title, artist, isPlaying) {
            if (!hasBridge()) return false;
            var s = readState();
            s.title = title || s.title;
            s.artist = artist || s.artist;
            if (typeof isPlaying === 'boolean') s.isPlaying = isPlaying;
            try {
                AndroidMediaBridge.updatePlaybackState(JSON.stringify(s));
                return true;
            } catch (e) {
                console.warn(TAG, 'updateMetadata fallito', e);
                return false;
            }
        }
    };

    // ------------------------------------------------------------
    // Lettura dello stato dal DOM / dagli elementi <audio>
    // ------------------------------------------------------------
    function textOf(selector) {
        var el = document.querySelector(selector);
        return el ? (el.textContent || '').trim() : '';
    }

    function firstAudio() {
        return document.querySelector('audio') || null;
    }

    function readState() {
        var state = { title: '', artist: '', album: '', artworkUrl: '', isPlaying: false, positionMs: 0, durationMs: 0 };

        // Header "Now Playing" del player
        var nowPlaying = document.querySelector('[data-testid="now-playing-widget"]');
        if (!nowPlaying) nowPlaying = document.querySelector('[data-testid="main-view"] [role="banner"]');
        if (nowPlaying) {
            var tEl = nowPlaying.querySelector('[data-testid="context-track-link"], a[href*="/track/"]');
            if (tEl) state.title = (tEl.textContent || '').trim();
            var aEl = nowPlaying.querySelector('[data-testid="context-info"] a[href*="/artist/"], a[href*="/artist/"]');
            if (aEl && aEl !== tEl) state.artist = (aEl.textContent || '').trim();
        }
        // Fallback: tooltip del marquee del brano
        if (!state.title) {
            var mq = document.querySelector('[data-testid="media-controls"] [aria-hidden="false"][class*="marquee"], [data-testid="media-controls"] span[aria-hidden="true"]');
            if (mq) state.title = (mq.textContent || '').trim();
        }

        // Copertina: img dentro l'header (i.scdn.co)
        if (nowPlaying) {
            var img = nowPlaying.querySelector('img[src*="scdn.co"], img[src^="https://i."]');
            if (img && img.src) state.artworkUrl = img.src;
        }

        // Stato play/pausa + posizione dal tag <audio> quando disponibile
        var audio = firstAudio();
        if (audio) {
            state.isPlaying = !audio.paused && !audio.ended;
            state.positionMs = Math.round((audio.currentTime || 0) * 1000);
            state.durationMs = Math.round((audio.duration || 0) * 1000);
            if (audio.duration > 0 && !state.title) {
                // alcuni build web nascondono il titolo: niente da fare qui
            }
        } else {
            // Fallback: icona del pulsante play/pause del player
            var btn = document.querySelector('[data-testid="control-button-playpause"]');
            if (btn) {
                var label = (btn.getAttribute('aria-label') || '').toLowerCase();
                state.isPlaying = label.indexOf('play') !== -1 ? false : (label.indexOf('pause') !== -1 ? true : state.isPlaying);
                var icon = btn.querySelector('svg[data-icon="play"], svg[class*="play"]:not([class*="pause"])');
                if (icon) state.isPlaying = true;
            }
        }

        return state;
    }

    // ------------------------------------------------------------
    // Invio al bridge Android (dedup + heartbeat di posizione)
    // ------------------------------------------------------------
    var lastSignature = '';
    var lastSentAt = 0;

    function report(force) {
        if (!hasBridge()) return;
        var s = readState();
        if (!s.title && !s.isPlaying) return; // pagina senza riproduzione: niente notifica
        var signature = s.title + '|' + s.artist + '|' + s.isPlaying;
        var now = Date.now();
        // Invia se cambiato qualcosa o ogni 5s (per la barra di avanzamento)
        if (!force && signature === lastSignature && (now - lastSentAt) < 5000) return;
        lastSignature = signature;
        lastSentAt = now;
        try {
            AndroidMediaBridge.updatePlaybackState(JSON.stringify(s));
        } catch (e) {
            console.warn(TAG, 'invio stato fallito', e);
        }
    }

    window.__spotifyWrapperReport = function () { report(true); };

    // ------------------------------------------------------------
    // Comandi dalla notifica verso la pagina
    // ------------------------------------------------------------
    function pressButton(testid) {
        var b = document.querySelector('[data-testid="' + testid + '"]');
        if (b) { b.click(); return true; }
        return false;
    }

    function cmdPlayPause(wantPlaying) {
        var audio = firstAudio();
        if (audio) {
            if (wantPlaying && audio.paused) {
                audio.play().catch(function () {
                    // Fallback 1: bottone play dedicato, Fallback 2: toggle.
                    if (!pressButton('control-button-play')) {
                        pressButton('control-button-playpause');
                    }
                });
            } else if (!wantPlaying && !audio.paused) {
                audio.pause();
            }
            setTimeout(function () { report(true); }, 300);
            return true;
        }
        return pressButton('control-button-playpause');
    }

    window.__spotifyWrapperCmd = function (cmd) {
        console.log(TAG, 'comando ricevuto:', cmd);
        // Il flag vive su window (condiviso con inject.js): dice al keep-alive
        // audio se la pausa e' voluta dall'utente o subita dal sistema.
        switch (cmd) {
            case 'play':
                window.__spotifyWrapperUserPaused = false;
                cmdPlayPause(true);
                break;
            case 'pause':
                window.__spotifyWrapperUserPaused = true;
                cmdPlayPause(false);
                break;
            case 'next':
                if (!pressButton('control-button-skip-next')) {
                    var a = firstAudio();
                    if (a) { a.currentTime = a.duration; } // forza track-ended -> next
                }
                break;
            case 'prev':
                if (!pressButton('control-button-skip-back')) {
                    var b = firstAudio();
                    if (b) { b.currentTime = 0; }
                }
                break;
        }
        setTimeout(function () { report(true); }, 600);
    };

    // ------------------------------------------------------------
    // Osservatori: DOM + eventi audio
    // ------------------------------------------------------------
    var scheduled = false;
    new MutationObserver(function () {
        if (scheduled) return;
        scheduled = true;
        requestAnimationFrame(function () { scheduled = false; report(false); });
    }).observe(document.documentElement, { childList: true, subtree: true, attributes: true });

    function bindAudio(el, force) {
        if (el.__wrapperBound && !force) return;
        el.__wrapperBound = true;
        ['play', 'pause', 'ended'].forEach(function (ev) {
            el.addEventListener(ev, function () { report(true); });
        });
        console.log(TAG, 'eventi audio collegati');
    }

    function rescanAudio(force) {
        document.querySelectorAll('audio').forEach(function (a) { bindAudio(a, force); });
    }

    function scanAudio() { rescanAudio(false); }
    scanAudio();
    setInterval(scanAudio, 3000);
    setInterval(function () { report(false); }, 5000); // heartbeat posizione

    exposeCommandApi();
    console.log(TAG, 'bridge media installato');
})();
