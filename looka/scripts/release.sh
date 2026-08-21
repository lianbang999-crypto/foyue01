#!/bin/bash
# Looka 一条命令发版：打包 → 上传 R2 → 更新 version.json → 部署
# 用法：改好 app/build.gradle.kts 的 versionCode/versionName 后运行 scripts/release.sh "更新说明"
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME=/Users/bincai/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home
GRADLE=/Users/bincai/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle

CHANGELOG="${1:-修复与体验优化}"
VC=$(grep 'versionCode = ' app/build.gradle.kts | grep -o '[0-9]*')
VN=$(grep 'versionName = ' app/build.gradle.kts | grep -o '"[^"]*"' | tr -d '"')

echo "▶ 构建 v$VN ($VC) ..."
"$GRADLE" -p . assembleRelease -q
APK=app/build/outputs/apk/release/app-release.apk
SHA=$(shasum -a 256 $APK | cut -d' ' -f1)
SIZE=$(stat -f%z $APK)

echo "▶ 上传 R2 ..."
cd server
npx wrangler r2 object put looka-apk/looka-latest.apk --file=../$APK \
  --content-type application/vnd.android.package-archive --remote

python3 - <<PYEOF
import json
v = json.load(open('public/version.json'))
v.update(versionCode=$VC, versionName='$VN', size=$SIZE, sha256='$SHA', changelog='''$CHANGELOG''')
json.dump(v, open('public/version.json','w'), ensure_ascii=False, indent=2)
PYEOF

echo "▶ 部署 Worker（version.json 生效）..."
npx wrangler deploy | tail -2
echo "✅ v$VN ($VC) 已发布 — 用户端次日启动会收到更新提示"
