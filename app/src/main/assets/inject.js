/* ============================================================
 * inject.js - UserScript + UserStyle per Spotify Web Player
 * (open.spotify.com) nella WebView dell'app wrapper.
 *
 * Contiene:
 *   1) Anti-ads (CSS statico + pulizia runtime + MutationObserver)
 *   2) UserStyle "app-like": ottimizza la UI desktop per il tocco mobile
 *   3) Utility JS: viewport forzato, touch->click, re-iniezione su SPA
 *
 * Debug: chrome://inspect -> Console (log con prefisso [Wrapper]/[AdBlocker]).
 * ============================================================ */
(function () {
    'use strict';

    var AD_TAG = '[AdBlocker]';
    var UI_TAG = '[Wrapper/UI]';

    /* ----------------------------------------------------------
     * SEZIONE CSS (UserStyle)
     * ---------------------------------------------------------- */

    // --- 1) CSS anti-ads (gia' presente, invariato) ---
    var adCss = [
        '[data-testid="ad-banner"]',
        '[data-testid^="leaderboard-leaderboard"]',
        '.ad-container',
        '.AdContainer',
        '[class*="SlotBox"]',           /* contenitori slot ads di tipo DFP */
        'section[data-testid="billboard"]',
        'div[aria-label*="Advertisement" i]',
        'div[aria-label*="Sponsorized" i]',
        'div[aria-label*="Sponsorizzato" i]'
    ].join(',\n') + ' { display: none !important; visibility: hidden !important; height: 0 !important; max-height: 0 !important; overflow: hidden !important; }';

    // --- 2) CSS "app-like" per il tocco su mobile ---
    var uiCss = [
        /* Reattivita': niente ritardo 300ms sui tap. Il selettore * non basta:
           alcuni widget desktop impostano touch-action proprio/ereditato, quindi
           lo forziamo !important anche su TUTTI gli elementi interattivi. */
        '*, *::before, *::after { touch-action: manipulation; }',
        'a, button, [role="button"], input, select, textarea, label, summary,',
        '[tabindex], [data-testid], .Button, [draggable="true"]',
        '{ touch-action: manipulation !important; }',

        /* Niente selezione accidentale del testo (input esclusi) */
        '*, *::before, *::after {',
        '  -webkit-user-select: none;',
        '  user-select: none;',
        '  -webkit-touch-callout: none;',
        '}',
        /* FIX TASTIERA: i campi di testo DEVONO restare interattivi. Le regole
           sopra (user-select/touch-action globali) possono interferire con il
           focus e la selezione in editazione: qui li riabilitiamo con
           !important, cosi' il tap su un input da sempre il focus e apre la IMM. */
        'input, textarea, select, [contenteditable="true"], [role="textbox"], [role="searchbox"] {',
        '  -webkit-user-select: text !important;',
        '  user-select: text !important;',
        '  touch-action: auto !important;',
        '}',

        /* FIX TASTIERA (Android/WebView): niente zoom del layout quando la IMM
           si apre. Con adjustResize+useWideViewPort Chromium "ricalcola" il
           viewport e la pagina desktop appare zoomata/fuori scala: force-zero
           zoom su html/body mantiene il layout stabile durante la digitazione. */
        'html, body { zoom: 1 !important; }',
        'body[data-ime-open="true"] { overflow: hidden !important; }',

        /* Scrollbar invisibili ma scroll funzionante */
        '::-webkit-scrollbar { width: 0 !important; height: 0 !important; display: none; }',
        '* { scrollbar-width: none !important; }',

        /* Nascondi elementi desktop invasivi: banner "get the app", upsell Premium,
           sidebar secondari/ridondanti. Selettori generici: se Spotify cambia
           struttura, qui al massimo resta visibile l'elemento (nessun danno). */
        '[data-testid="upgrade-button"],',                            /* CTA "Upgrade to Premium" header */
        'header [data-testid="cta-button"],',
        'a[data-testid="download-app-button"],',
        '[data-testid="download-link"],',
        '[id*="banner-wrapper" i],',                                  /* topbars promo */
        '[data-component-type="standalone-hero-banner"],',
        '[data-component-type="notification-banner"],',
        'section[aria-label*="Get Spotify" i],',
        'aside[aria-label*="Get on your phone" i],',
        'div[class*="premium-upsell" i],',
        'div[class*="PremiumUpsell" i]',
        '{ display: none !important; }',

        /* Sidebar "Now playing" espandibile: su mobile occupa mezzo schermo */
        'aside[data-testid="now-playing-widget"] { display: none !important; }',

        /* Colonne ridondanti nelle tabelle tracce (album / aggiungi ai preferiti) */
        '[data-testid="collection-row-separator"],',
        'td[data-testid="column-content-add"],',
        'span[data-testid="add-to-playlist-button"],',
        'button[data-testid="add-to-playlist-button"]',
        '{ display: none !important; }',

        /* Layout a piena larghezza: contenitori e griglie desktop */
        '.main-view-grid__main,',
        '[data-testid="view"],',
        'main',
        '{ min-width: 0 !important; width: 100% !important; padding-left: 8px !important; padding-right: 8px !important; }',

        /* Playlist/album: una sola colonna di card larga, niente colonne extra */
        '[data-testid="main-content"] .container-suggest-cards-list,',
        '[data-testid="main-content"] .glue-page-wrapper__content,',
        '[data-testid="main-content"] section > div[class*="main-grid"]',
        '{ grid-template-columns: 1fr 1fr !important; gap: 8px !important; }',

        /* Riga traccia: nascondi la copertina duplicata quando gia\' mostrata */
        '[data-testid="tracklist-row"] [data-testid="image"] img { border-radius: 4px; }',

        /* Player inferiore: barra fissa pulita e alta abbastanza per il tocco */
        'footer[data-testid="player"],',
        '#nowplaying-bar,',
        '[data-testid="now-playing-bar"]',
        '{',
        '  position: fixed !important;',
        '  bottom: 0 !important;',
        '  left: 0 !important;',
        '  right: 0 !important;',
        '  min-height: 70px !important;',
        '  z-index: 9999 !important;',
        '  padding-bottom: env(safe-area-inset-bottom, 0px) !important;',
        '}',
        /* Lascia spazio sotto i contenuti cosi\' l\'ultima riga non e\' coperta dal player */
        'body { padding-bottom: 96px !important; }',

        /* Pulsanti principali del player: area tocco >= 48px */
        'footer button[data-testid="play-pause"],',
        'footer button[data-testid="previous"],',
        'footer button[data-testid="next"],',
        'footer button[data-testid="shuffle"],',
        'footer button[data-testid="repeat"]',
        '{',
        '  min-width: 48px !important;',
        '  min-height: 48px !important;',
        '}',
        'footer button[data-testid="play-pause"] {',
        '  min-width: 56px !important;',
        '  min-height: 56px !important;',
        '}',

        /* Font piu\' grandi per titoli brano/artista e pulsanti principali */
        '[data-testid="now-playing-widget"] [data-testid="media-player-info"] .encore-informative-text,',
        '.marquee-inner span.encore-informative-text',
        '{ font-size: 16px !important; line-height: 1.3 !important; }',
        '[data-testid="media-player-info"] .encore-bold-text-element { font-size: 15px !important; }',
        'h1.encore-bold-title-medium, h1.encore-bold-title-large { font-size: 24px !important; }',
        'button[data-testid="play-button"] { min-height: 48px !important; min-width: 48px !important; }'
    ].join('\n');

    function injectStyle(id, cssText) {
        if (!document.getElementById(id)) {
            var el = document.createElement('style');
            el.id = id;
            el.appendChild(document.createTextNode(cssText));
            (document.head || document.documentElement).appendChild(el);
        } else {
            // Il contenuto puo' cambiare a caldo (nuovo tema/visibilita'):
            // sovrascriviamo sempre il testo del nodo esistente.
            var existing = document.getElementById(id);
            existing.textContent = cssText;
        }
    }

    /* ----------------------------------------------------------
     * PREFERENZE DI PERSONALIZZAZIONE (temi + visibilita')
     * Lette dal bridge nativo AndroidPrefsBridge.getPrefs() (JSON).
     * I temi lavorano sugli CSS custom property di Spotify
     * (--background-base, --text-base, ecc.) cosi' non dipendono
     * dalle classi, che cambiano spesso.
     * ---------------------------------------------------------- */
    var DEFAULT_PREFS = { theme: 'default', hideNowPlaying: false, hideSidebar: false, biggerFonts: false };
    var prefsCache = Object.assign({}, DEFAULT_PREFS);

    function readPrefs() {
        try {
            if (window.AndroidPrefsBridge && AndroidPrefsBridge.getPrefs) {
                return Object.assign({}, DEFAULT_PREFS, JSON.parse(AndroidPrefsBridge.getPrefs()));
            }
        } catch (e) {
            console.log(UI_TAG, 'lettura preferenze fallita:', e.message);
        }
        return Object.assign({}, DEFAULT_PREFS);
    }

    function buildThemeCss(theme) {
        if (theme === 'amoled') {
            return [
                ':root {',
                '  --background-base: #000000 !important;',
                '  --background-highlight: #121212 !important;',
                '  --background-press: #000000 !important;',
                '  --backdrop-base: #000000 !important;',
                '  --decorative-base: #000000 !important;',
                '}',
                'body, html, .main-view, #main, .Root, .Root__main-view,',
                '.main-topBar-container, .main-leftNav-container, footer',
                '{ background-color: #000000 !important; }'
            ].join('\n');
        }
        if (theme === 'dark_violet') {
            return [
                ':root {',
                '  --background-base: #14081f !important;',
                '  --background-highlight: #23103a !important;',
                '  --background-press: #0d0516 !important;',
                '  --text-base: #f2e9fb !important;',
                '  --misc-on-notification-default: #f2e9fb !important;',
                '}',
                'body, html, .main-view, #main, .Root, .Root__main-view',
                '{ background-color: #14081f !important; }',
                '[data-testid="play-button"], .main-playButton-primary button',
                '{ background-color: #8b3fd6 !important; color: #ffffff !important; }'
            ].join('\n');
        }
        return ''; /* default: nessun override */
    }

    function buildVisibilityCss(p) {
        var rules = [];
        if (p.hideNowPlaying) {
            rules.push('aside[data-testid="now-playing-widget"] { display: none !important; }');
        }
        if (p.hideSidebar) {
            rules.push(
                '.Root__nav, nav[role="navigation"], aside[role="navigation"],',
                '.LeftNav, .main-leftNav-container',
                '{ display: none !important; }'
            );
        }
        if (p.biggerFonts) {
            rules.push(
                'html { font-size: 112.5% !important; }',
                '.encore-informative-text, .encore-bold-text-element { letter-spacing: 0.01em; }',
                'footer button { min-width: 48px !important; min-height: 48px !important; }'
            );
        }
        return rules.join('\n');
    }

    function applyStyles() {
        injectStyle('spotify-wrapper-adblock-css', adCss);
        injectStyle('spotify-wrapper-ui-css', uiCss);
        injectStyle('spotify-wrapper-theme-css', buildThemeCss(prefsCache.theme));
        injectStyle('spotify-wrapper-visibility-css', buildVisibilityCss(prefsCache));
    }

    /** API interna: MainActivity la chiama quando le preferenze cambiano. */
    window.__spotifyWrapperApplyPrefs = function () {
        prefsCache = readPrefs();
        applyStyles();
        console.log(UI_TAG, 'preferenze applicate:', JSON.stringify(prefsCache));
    };

    /* ----------------------------------------------------------
     * VIEWPORT FORZATO
     * Impedisce lo zoom-out fastidioso della pagina desktop.
     * ---------------------------------------------------------- */
    function forceViewport() {
        var content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
        var meta = document.querySelector('meta[name="viewport"]');
        if (!meta) {
            meta = document.createElement('meta');
            meta.name = 'viewport';
            (document.head || document.documentElement).appendChild(meta);
        }
        if (meta.getAttribute('content') !== content) {
            meta.setAttribute('content', content);
            console.log(UI_TAG, 'viewport forzato:', content);
        }
    }

    /* ----------------------------------------------------------
     * ANTI-ADS RUNTIME (logica preesistente, invariata)
     * ---------------------------------------------------------- */
    var AD_TEXT_RE = /(advertisement|sponsored by|sponsorizzato|pubblicita|annun?cio pubbl)/i;
    var hiddenCount = 0;

    function looksLikeAd(el) {
        if (!el || el.nodeType !== 1) return false;
        var testId = el.getAttribute && el.getAttribute('data-testid');
        if (testId && /ad[-_]?banner|leaderboard/i.test(testId)) return true;
        var aria = el.getAttribute && el.getAttribute('aria-label');
        if (aria && AD_TEXT_RE.test(aria)) return true;
        // Solo elementi piccoli/medi: mai nascondere il player o l'app intera
        var txt = el.textContent || '';
        if (txt.length > 300) return false;
        return AD_TEXT_RE.test(txt);
    }

    function hideElement(el) {
        if (el.style.display === 'none') return;
        el.style.setProperty('display', 'none', 'important');
        hiddenCount++;
        console.log(AD_TAG, 'elemento nascosto:',
            el.tagName.toLowerCase() +
            (el.className ? '.' + String(el.className).split(' ')[0] : ''),
            '(totale: ' + hiddenCount + ')');
    }

    function cleanAds(root) {
        if (!root || !root.querySelectorAll) return;
        var candidates = root.querySelectorAll(
            '[data-testid*="ad"], [aria-label*="dvertis"], [class*="ad-"], [class*="-ad"], .ad-container'
        );
        candidates.forEach(function (el) {
            if (looksLikeAd(el)) hideElement(el);
        });
        var texts = root.querySelectorAll('span, div, p, h1, h2, h3, a');
        texts.forEach(function (el) {
            var t = (el.textContent || '').trim();
            if (t && t.length < 60 && AD_TEXT_RE.test(t)) {
                var target = el.closest('section, aside, div[class], div[data-testid]') || el.parentElement || el;
                if (target !== document.body && target !== document.documentElement) {
                    hideElement(target);
                }
            }
        });
    }

    window.__spotifyWrapperCleanAds = function () { cleanAds(document.body); };

    /* ----------------------------------------------------------
     * TOUCH -> CLICK
     * Alcuni widget desktop rispondono solo a mousedown/mouseup:
     * al tap sintetizziamo la sequenza mouse completa sull'elemento
     * sotto il dito, cosi\' l'azione parte subito.
     * ---------------------------------------------------------- */
    function synthesizeClickFromTouch(touch) {
        var x = touch.clientX, y = touch.clientY;
        var el = document.elementFromPoint(x, y);
        if (!el || el === document.body) return;
        var opts = {
            bubbles: true, cancelable: true, view: window,
            clientX: x, clientY: y, screenX: touch.screenX, screenY: touch.screenY,
            button: 0, buttons: 1
        };
        // Marca l'evento come generato dal wrapper: la finestra WebView non puo'
        // impostare isTrusted, quindi il guard anti-doppio-click sotto filtra
        // solo i click NATIVI e lascia passare questi.
        Object.defineProperty(opts, 'isTrustedWrapper', { value: true });
        try {
            el.dispatchEvent(new MouseEvent('mousedown', opts));
            el.dispatchEvent(new MouseEvent('mouseup', Object.assign({}, opts, { buttons: 0 })));
            el.dispatchEvent(new MouseEvent('click', Object.assign({}, opts, { buttons: 0 })));
        } catch (e) {
            console.log(UI_TAG, 'sintesi click fallita:', e.message);
        }
    }

    function installTouchBridge() {
        if (window.__spotifyWrapperTouchBridge) return;
        window.__spotifyWrapperTouchBridge = true;
        // Strategia "first-touch": il click sintetizzato parte su touchstart,
        // quindi l'azione e' immediata. Il click NATIVO che Chromium genera
        // dopo ~300ms viene neutralizzato dal guard in capture-phase sotto:
        // niente doppio toggle (era questo a dare l'impressione del "due tap").
        var lastSynthAt = 0;

        document.addEventListener('touchstart', function (ev) {
            var t = ev.touches && ev.touches[0];
            if (!t) return;
            var el = document.elementFromPoint(t.clientX, t.clientY);
            if (!el || el === document.body) return;
            // FIX TASTIERA: mai sintetizzare click su campi di testo. La sintesi
            // su touchstart fa scattare il default del browser PRIMA che Chromium
            // registri il focus sull'input: la tastiera non si apre. Lasciamo
            // passare il tap nativo (e' quello che apre la IMM).
            if (el.closest('input, textarea, select, [contenteditable="true"], [role="textbox"], [role="searchbox"]')) return;
            // Slider del player: hanno handler pointer propri, non toccarli.
            if (el.closest('input[type="range"], .progress-bar, [role="slider"]')) return;
            // Solo elementi interattivi: niente sintesi su testo/card passive.
            var target = el.closest('button, a, [role="button"], [data-testid]');
            if (!target || target === document.body) return;
            lastSynthAt = Date.now();
            synthesizeClickFromTouch(t);
        }, { passive: true });

        document.addEventListener('click', function (ev) {
            // Evita SOLO il click nativo ritardato di Chromium: i nostri eventi
            // sintetici portano il marker e devono propagarsi.
            if (ev.isTrustedWrapper || !ev.isTrusted) return;
            // FIX TASTIERA: non neutralizzare MAI i click nativi su input/textarea:
            // sono l'unico modo con cui Chromium trasferisce il focus al campo e
            // mostra la tastiera. Se li bloccassimo, login/ricerca resterebbero muti.
            var tgt = ev.target;
            if (tgt && tgt.closest && tgt.closest(
                'input, textarea, select, [contenteditable="true"], [role="textbox"], [role="searchbox"]'
            )) return;
            if (Date.now() - lastSynthAt < 700) {
                ev.stopPropagation();
                ev.preventDefault();
            }
        }, true);

        console.log(UI_TAG, 'bridge touch->click attivo (first-touch)');
    }

    /* ----------------------------------------------------------
     * FOCUS BRIDGE (per il tasto Indietro "intelligente")
     * Notifica l'activity quando un campo del DOM riceve o perde il
     * focus, cosi' MainActivity sa se la pressione di Indietro deve
     * chiudere la tastiera (IMM) invece di navigare nella cronologia.
     * ---------------------------------------------------------- */
    function installFocusBridge() {
        if (window.__spotifyWrapperFocusBridge) return;
        window.__spotifyWrapperFocusBridge = true;
        var INPUT_SEL = 'input, textarea, select, [contenteditable="true"], [role="textbox"], [role="searchbox"]';

        function notifyFocus(el) {
            if (typeof AndroidMediaBridge === 'undefined' ||
                !AndroidMediaBridge.onFocusChanged) return;
            try {
                AndroidMediaBridge.onFocusChanged(!!(el && el.closest && el.closest(INPUT_SEL)));
            } catch (e) { /* bridge non pronto: ignora */ }
        }

        // focusin/focusout bubbles: coprono anche i nodi creati dinamicamente.
        document.addEventListener('focusin', function (ev) {
            notifyFocus(ev.target);
        }, true);
        document.addEventListener('focusout', function (ev) {
            // Piccolo ritardo: se lo spostamento e' tra due campi vicini,
            // focusin del nuovo campo arriva prima e sovrascrive correttamente.
            setTimeout(function () {
                var ae = document.activeElement;
                notifyFocus(ae && ae !== document.body ? ae : null);
            }, 120);
        }, true);

        console.log(UI_TAG, 'focus bridge attivo');
    }

    /* ----------------------------------------------------------
     * MUTATION OBSERVER UNIFICATO
     * Riapplica stili/viewport/pulizia ads a ogni cambio DOM (SPA):
     * Spotify rimuove il nostro <style> o riscrive il <head> durante
     * le navigazioni interne, quindi va ri-verificato, non osservato
     * una volta sola. Debounce via requestAnimationFrame.
     * ---------------------------------------------------------- */
    var scheduled = false;
    var observer = new MutationObserver(function () {
        if (scheduled) return;
        scheduled = true;
        requestAnimationFrame(function () {
            scheduled = false;
            applyStyles();
            forceViewport();
            cleanAds(document.body);
        });
    });

    function startObserver() {
        observer.observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['data-testid', 'aria-label', 'class']
        });
        console.log(AD_TAG, 'MutationObserver attivo');
    }

    /* ----------------------------------------------------------
     * PAGE VISIBILITY SPOOF
     * Quando la WebView va in background o lo schermo si spegne,
     * Chromium riporta document.hidden=true / visibilityState='hidden':
     * Spotify mette in pausa l'audio e la sessione risulta offline dal PC.
     * Fingiamo una finestra SEMPRE visibile; l'app nativa notifica il
     * cambio reale via __spotifyWrapperVisibility(bool) (MainActivity).
     * ---------------------------------------------------------- */
    var appVisible = true;

    function installVisibilitySpoof() {
        if (window.__spotifyWrapperVisSpoof) return;
        window.__spotifyWrapperVisSpoof = true;

        Object.defineProperty(document, 'hidden', {
            configurable: true,
            get: function () { return false; }
        });
        Object.defineProperty(document, 'visibilityState', {
            configurable: true,
            get: function () { return 'visible'; }
        });

        // Blocca i listener di visibilitychange/pagehide della pagina:
        // cosi' non possono reagire all'oscuramento reale della WebView.
        var origAdd = EventTarget.prototype.addEventListener;
        EventTarget.prototype.addEventListener = function (type, listener, options) {
            if ((type === 'visibilitychange' || type === 'pagehide') &&
                (this === document || this === window)) {
                return; // ignorato
            }
            return origAdd.call(this, type, listener, options);
        };

        // API interna: MainActivity la chiama quando l'app cambia visibilita'.
        window.__spotifyWrapperVisibility = function (visible) {
            appVisible = !!visible;
            console.log(UI_TAG, 'visibilita' + String.fromCharCode(39) + ' segnalata:', appVisible);
        };
        console.log(UI_TAG, 'visibility spoof attivo');
    }

    /* ----------------------------------------------------------
     * BOOTSTRAP
     * ---------------------------------------------------------- */
    function bootstrap() {
        installVisibilitySpoof();
        prefsCache = readPrefs();
        applyStyles();
        forceViewport();
        installTouchBridge();
        installFocusBridge();
        cleanAds(document.body);
        if (!window.__spotifyWrapperObserverStarted) {
            window.__spotifyWrapperObserverStarted = true;
            startObserver();
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', bootstrap);
    } else {
        bootstrap();
    }

    // Pulizia periodica di sicurezza (nodi mutati senza mutation visibili)
    if (!window.__spotifyWrapperHeartbeat) {
        window.__spotifyWrapperHeartbeat = true;
        setInterval(function () {
            applyStyles();
            forceViewport();
            cleanAds(document.body);
        }, 5000);
    }

    /* ----------------------------------------------------------
     * KEEP-ALIVE AUDIO (anti-sospensione WebView)
     * Alcune build Chromium mettono in pausa gli HTMLMediaElement
     * quando la finestra e' oscurata, nonostante lo spoof di
     * visibility. Se il web player risulta fermo MA l'app nativa
     * non ha registrato una pausa utente (__spotifyWrapperUserPaused),
     * riavviamo l'elemento audio: la musica continua a schermo
     * spento e la sessione Spotify resta online dal PC.
     * ---------------------------------------------------------- */
    if (!window.__spotifyWrapperAudioKeepAlive) {
        window.__spotifyWrapperAudioKeepAlive = true;
        setInterval(function () {
            var a = document.querySelector('audio');
            if (!a || !a.paused || a.ended) return;
            // Pausa voluta dall'utente (notifica/UI): non insistere.
            if (window.__spotifyWrapperUserPaused) return;
            console.log(UI_TAG, 'audio fermato dal sistema: riavvio keep-alive');
            var p = a.play();
            if (p && p.catch) p.catch(function () { /* gesture policy: ignora */ });
        }, 2000);
    }

    console.log(UI_TAG, 'userstyle/usercript applicati (ads-block + layout app-like)');
})();
