// 佛乐 Service Worker：壳资源缓存提速 + 离线兜底。
// 音频（/audio/*，Range 分段）与问道接口（/api/*）不经缓存，永远直连网络。
// 改动壳资源清单或需要强制刷新客户端缓存时，把 VER 加一。

const VER = 'fy-v18';
const SHELL = [
  '/',
  '/css/style.css',
  '/js/app.js',
  '/js/station.js',
  '/js/intros.js',
  '/js/qrcode.js',
  '/js/zh-t.js',
  '/js/i18n.js',
  '/favicon.svg',
  '/icon-512.png',
  '/manifest.webmanifest',
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(VER).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VER).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  // 音频流（含 Range 分段）与问道接口：直连，不缓存
  if (url.pathname.startsWith('/audio/') || url.pathname.startsWith('/api/') || req.headers.get('range')) return;

  // 页面导航：网络优先（保证部署即生效），离线回退缓存壳
  // 只把首页写入 '/' 兜底位，避免 /admin.html 等其他导航页污染离线壳
  if (req.mode === 'navigate') {
    e.respondWith(
      fetch(req).then((r) => {
        if (url.pathname === '/') {
          const copy = r.clone();
          caches.open(VER).then((c) => c.put('/', copy));
        }
        return r;
      }).catch(() => caches.match('/'))
    );
    return;
  }

  // 其余静态资源与数据（css/js/json/text）：缓存优先 + 后台更新（下次访问用新版本）
  e.respondWith(
    caches.match(req).then((cached) => {
      const refresh = fetch(req).then((r) => {
        if (r.ok) {
          const copy = r.clone();
          caches.open(VER).then((c) => c.put(req, copy));
        }
        return r;
      }).catch(() => cached);
      return cached || refresh;
    })
  );
});
