// 自知录 Service Worker：静态壳离线可用；接口与媒体始终走网络
const VERSION = 'zzl-202608181238';
const CORE = ['/', '/style.css', '/app.js', '/manifest.webmanifest'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(CORE)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// 每日提醒（无载荷推送：服务端只发信号，文案在此固定）
self.addEventListener('push', e => {
  e.waitUntil(self.registration.showNotification('自知录', {
    body: '今日功过还未记，回来省察一笔吧。',
    icon: '/icons/icon-192.png',
    badge: '/icons/icon-192.png',
    tag: 'daily-remind'
  }));
});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil(clients.matchAll({ type: 'window', includeUncontrolled: true }).then(ws => {
    for (const w of ws) if ('focus' in w) return w.focus();
    return clients.openWindow('/');
  }));
});

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  // 仅处理同源 GET 的静态请求；/api 与 /media 不缓存
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/media/')) return;
  // 网络优先，失败回缓存（保证更新及时，离线仍有壳）
  e.respondWith(
    fetch(e.request)
      .then(r => {
        const copy = r.clone();
        caches.open(VERSION).then(c => c.put(e.request, copy));
        return r;
      })
      .catch(() => caches.match(e.request).then(hit => hit || caches.match('/')))
  );
});
