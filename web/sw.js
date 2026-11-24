// Offline support: the app shell is cached so PaperFeed opens without a connection.
// The search model is cached separately by the worker (pf-model-v1). API calls always go to the network.
const VERSION = 'pf-shell-v1';
const SHELL = [
  './', 'index.html', 'manifest.webmanifest', 'css/app.css',
  'js/app.js', 'js/store.js', 'js/net.js', 'js/text.js', 'js/search.js', 'js/icons.js', 'js/embed-worker.js',
  'vendor/ort.wasm.min.mjs', 'vendor/ort-wasm-simd-threaded.mjs',
  'icons/icon.svg', 'icons/icon-192.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(
    keys.filter((k) => k.startsWith('pf-shell-') && k !== VERSION).map((k) => caches.delete(k)),
  )).then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  if (url.pathname.includes('/model/')) return; // handled by the worker's own cache
  // ONNX Runtime files are big and never change for a given version: cache first.
  if (url.pathname.includes('/vendor/')) {
    e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request).then((r) => {
      if (r.ok) { const copy = r.clone(); caches.open('pf-vendor-v1').then((c) => c.put(e.request, copy)); }
      return r;
    })));
    return;
  }
  // Network first (so updates arrive), falling back to the cache when offline.
  e.respondWith(
    fetch(e.request).then((r) => {
      if (r.ok) {
        const copy = r.clone();
        caches.open(VERSION).then((c) => c.put(e.request, copy));
      }
      return r;
    }).catch(() => caches.match(e.request, { ignoreSearch: true }).then((r) => r || caches.match('index.html'))),
  );
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window' }).then((list) => (list[0] ? list[0].focus() : self.clients.openWindow('./'))));
});
