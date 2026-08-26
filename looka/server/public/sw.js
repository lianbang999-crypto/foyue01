/* Looka PWA Service Worker：只缓存应用外壳，API 永远走网络（避免旧缓存卡版本） */
const VER = 'looka-v41';
const SHELL = ['/', '/style.css', '/app.js', '/deer.svg', '/manifest.webmanifest', '/icon-192.png', '/icon-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VER).then(c => c.addAll(SHELL)));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys =>
    Promise.all(keys.filter(k => k !== VER).map(k => caches.delete(k)))
  ).then(() => self.clients.claim()));
});
self.addEventListener('message', e => { if (e.data === 'skip') self.skipWaiting(); });
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.pathname.startsWith('/api/') || url.pathname.startsWith('/dl/')) return;
  // 网络优先、缓存兜底：保证部署即生效，离线仍可打开
  e.respondWith(
    fetch(e.request).then(resp => {
      const copy = resp.clone();
      caches.open(VER).then(c => c.put(e.request, copy)).catch(() => { });
      return resp;
    // X2（§70）：ignoreSearch —— 页面请求带 ?v= 版本参数，预缓存存的是裸路径，离线时也要能对上
    }).catch(() => caches.match(e.request, { ignoreSearch: true }).then(m => m || caches.match('/')))
  );
});
