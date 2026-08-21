#!/bin/bash
# Looka 一键部署到 Cloudflare（looka.foyue.org）
# 首次运行会：创建 looka-db → 回填 database_id → 初始化表 → 部署
# 之后运行：直接部署
set -e
cd "$(dirname "$0")"

if grep -q "TO_FILL_LOOKA_DB_ID" wrangler.jsonc; then
  echo "▸ 创建 D1 数据库 looka-db…"
  npx wrangler d1 create looka-db > /tmp/looka_d1_create.log 2>&1 || true
  ID=$(npx wrangler d1 info looka-db 2>/dev/null | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1)
  if [ -z "$ID" ]; then
    ID=$(grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' /tmp/looka_d1_create.log | head -1)
  fi
  [ -z "$ID" ] && { echo "✗ 无法获取 looka-db 的 database_id，请手动执行 npx wrangler d1 create looka-db 后填入 wrangler.jsonc"; exit 1; }
  sed -i '' "s/TO_FILL_LOOKA_DB_ID/$ID/" wrangler.jsonc
  echo "  looka-db = $ID"
fi

echo "▸ 初始化表结构（幂等）…"
npx wrangler d1 execute looka-db --remote --file=schema.sql -y

echo "▸ 部署 Worker + 网页端…"
npx wrangler deploy

echo ""
echo "✓ 完成：https://looka.foyue.org"
echo "首次部署后需设置密钥（只需一次）："
echo "  npx wrangler secret put SILICONFLOW_KEY   # 硅基流动 API Key"
echo "  npx wrangler secret put ADMIN_KEY         # 管理口令（生成兑换码用）"
