#!/bin/bash
# 自知录一键部署：自动刷新 Service Worker 版本号（避免老客户端缓存不更新），再发布到 Cloudflare
set -e
cd "$(dirname "$0")"
V="zzl-$(date +%Y%m%d%H%M)"
sed -i '' "s/const VERSION = '[^']*'/const VERSION = '$V'/" public/sw.js
echo "SW 版本 → $V"
npx wrangler deploy
