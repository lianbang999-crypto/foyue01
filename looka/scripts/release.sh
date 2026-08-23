#!/bin/bash
# Looka 一条命令发版：打包 → 上传 R2 → 更新 version.json → 部署
# 用法：改好 app/build.gradle.kts 的 versionCode/versionName 后运行 scripts/release.sh "更新说明"
set -e

# X12（§67）：先上 GitHub 再部署 —— 推送失败就中止，线上不允许跑仓库里没有的代码
echo "▶ 推送 GitHub（looka 私库）..."
git -C "$(git rev-parse --show-toplevel)" push looka main || { echo "❌ GitHub 推送失败，中止发版"; exit 1; }
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

# X2（§70）：静态资源版本盖章 —— 浏览器缓存旧 app.js 会让"已修复"的 bug 继续出现在用户面前
python3 - <<PYEOF
import re
p = 'public/index.html'
s = open(p).read()
s = re.sub(r'(app\.js\?v=)[\w.]*', r'\g<1>$VC', s)
s = re.sub(r'(style\.css\?v=)[\w.]*', r'\g<1>$VC', s)
open(p, 'w').write(s)
sw = 'public/sw.js'
t = open(sw).read()
t = re.sub(r"VER = 'looka-v[\w.]*'", "VER = 'looka-v$VC'", t)
open(sw, 'w').write(t)
print('  静态资源与 SW 已盖章 v$VC')
PYEOF

echo "▶ 部署 Worker（version.json 生效）..."
npx wrangler deploy | tail -2
echo "✅ v$VN ($VC) 已发布 — 用户端次日启动会收到更新提示"
