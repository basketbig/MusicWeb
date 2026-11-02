/* ============================================================
 * inject.js - Anti-ads per Spotify Web Player (open.spotify.com)
 * Iniettato dalla WebView via evaluateJavascript ad ogni pagina.
 * Apri chrome://inspect -> Console per vedere i log [AdBlocker].
 * ============================================================ */
(function () {
    'use strict';

    if (window.__spotifyWrapperInjected) {
        // Gia' iniettato: ri-esegui solo la pulizia, niente secondo observer
        window.__spotifyWrapperCleanAds();
        return;
    }
    window.__spotifyWrapperInjected = true;

    var TAG = '[AdBlocker]';

    // --- 1) CSS statico per nascondere i banner noti ---
    var css = [
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

    var styleEl = document.createElement('style');
    styleEl.id = 'spotify-wrapper-adblock-css';
    styleEl.appendChild(document.createTextNode(css));
    (document.head || document.documentElement).appendChild(styleEl);

    // --- 2) Pulizia runtime: cerca elementi ads "per contenuto" ---
    // Selettori generici: funzionano anche se Spotify cambia le classi.
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
        console.log(TAG, 'elemento nascosto:',
            el.tagName.toLowerCase() +
            (el.className ? '.' + String(el.className).split(' ')[0] : ''),
            '(totale: ' + hiddenCount + ')');
    }

    function cleanAds(root) {
        if (!root || !root.querySelectorAll) return;
        // 1) candidati con attributi tipici degli ads
        var candidates = root.querySelectorAll(
            '[data-testid*="ad"], [aria-label*="dvertis"], [class*="ad-"], [class*="-ad"], .ad-container'
        );
        candidates.forEach(function (el) {
            if (looksLikeAd(el)) hideElement(el);
        });
        // 2) span/div di testo breve che dicono "Advertisement"/"Sponsorizzato"
        var texts = root.querySelectorAll('span, div, p, h1, h2, h3, a');
        texts.forEach(function (el) {
            var t = (el.textContent || '').trim();
            if (t && t.length < 60 && AD_TEXT_RE.test(t)) {
                // risali al contenitore diretto (slot banner), non al nodo foglia
                var target = el.closest('section, aside, div[class], div[data-testid]') || el.parentElement || el;
                if (target !== document.body && target !== document.documentElement) {
                    hideElement(target);
                }
            }
        });
    }

    window.__spotifyWrapperCleanAds = function () { cleanAds(document.body); };

    // --- 3) MutationObserver: gli ads SPA arrivano dopo il caricamento ---
    var scheduled = false;
    var observer = new MutationObserver(function () {
        if (scheduled) return;
        scheduled = true;
        requestAnimationFrame(function () {
            scheduled = false;
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
        console.log(TAG, 'MutationObserver attivo');
    }

    cleanAds(document.body);
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            startObserver();
            cleanAds(document.body);
        });
    } else {
        startObserver();
    }

    // Pulizia periodica di sicurezza (nel caso di nodi mutati senza mutation visibili)
    setInterval(function () { cleanAds(document.body); }, 5000);

    console.log(TAG, 'script anti-ads iniettato con successo');
})();
