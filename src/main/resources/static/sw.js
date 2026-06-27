/* RAG Q&A service worker — minimal, security-conscious.
 *
 * Strategy: NETWORK-FIRST. We deliberately do NOT cache RAG answers, HTMX
 * fragments, SSE streams, or any authenticated response (privacy + auth-cookie
 * safety). The only thing cached is a static offline shell so navigations show
 * a friendly page instead of the browser's dinosaur when the network is down.
 */
const CACHE = 'rag-shell-v1';
const OFFLINE_URL = '/offline.html';
const PRECACHE = [OFFLINE_URL, '/icons/icon.svg', '/manifest.webmanifest'];

self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE).then((c) => c.addAll(PRECACHE)).then(() => self.skipWaiting())
    );
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys()
            .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
            .then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', (event) => {
    const req = event.request;

    // Only handle top-level GET page navigations. Form POSTs (login, signup,
    // logout, new-chat), HTMX fragments, SSE, and API calls fall through to the
    // network untouched — never re-issue a POST or serve a 200 offline page for one.
    if (req.mode !== 'navigate' || req.method !== 'GET') return;

    event.respondWith(
        fetch(req).catch(() => caches.match(OFFLINE_URL))
    );
});
