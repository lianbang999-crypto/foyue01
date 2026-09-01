#!/usr/bin/env python3
"""把站点内容打进安卓 APP 的 assets —— 让 APP 装完即可离线恭读、念佛、听已下载的音频。

为什么要有这一步：
  网页版每读一篇讲记都要联网现取，站点在 Cloudflare 上，国内网络一波动就是「打不开」。
  APP 把壳与 241 篇讲记正文随安装包装进手机，网络只用于音频流、问道与莲号等接口、
  以及目录更新。**打包前必须先跑这一步，漏了装出来是个没有正文的空壳。**

打包哪些、不打包哪些：
  · 打包 —— index.html、js/、img/、图标、manifest，三份目录（catalog/library/qa），
            以及 text/ 全量 1075 篇正文（约 17MB，是包体的大头，也正是离线的意义）。
  · 打包 —— css/all.css：这一份磁盘上并不存在！线上是 worker/css.js 在边缘按 ORDER
            把 12 个源文件拼出来的。此处照同一个 ORDER 先拼好写进包里 ——
            漏了它，APP 装出来是一堆没有样式的裸文字。
            ORDER 不在这里抄第二份，直接从 worker/css.js 里读，免得两处走岔。
  · 不打包 —— sw.js：内容已在本地，再套一层 Service Worker 只会和取件台互相打架。
            app.js 会按 window.__fyNative 判断，在 APP 里跳过注册。
  · 不打包 —— app/：安装包自己与 release.json。让它走网络现取，APP 才查得到新版；
            打进包里就成了「拿自己的版本比自己」，永远说已是最新。
  · 不打包 —— admin.html、_headers、robots.txt：后台与服务端专用。
  · 不打包 —— css/ 的 12 个源文件：页面只 link 那一份 all.css，带上只是白占体积。

顺带产出两份：
  · assets/content-version.json  —— 随 APK 出厂，记「这个包里的内容是哪一版」
  · public/app/release.json      —— 发到线上，网页与 APP 都拿它查最新版与下载地址

用法：
  python3 scripts/build-app-assets.py            # 打包
  python3 scripts/build-app-assets.py --check    # 只看会打包什么，不写任何文件
"""
import hashlib
import io
import json
import os
import re
import shutil
import sys
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PUBLIC = os.path.join(ROOT, "public")
ASSETS = os.path.join(ROOT, "app-android", "app", "src", "main", "assets")
CSS_JS = os.path.join(ROOT, "worker", "css.js")
GRADLE = os.path.join(ROOT, "app-android", "app", "build.gradle")
RELEASE_OUT = os.path.join(PUBLIC, "app", "release.json")

# 整目录拷贝
DIRS = ["js", "img", "text"]
# 根目录下的单文件。icon-512.png 另有一用：MediaService 拿它做锁屏封面，不可去掉
FILES = [
    "index.html", "manifest.webmanifest",
    "favicon.png", "icon-192.png", "icon-512.png",
    "catalog.json", "library.json", "qa.json",
]
SKIP_NAMES = {".DS_Store", "sw.js"}


def sha(path, n=12):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:n]


def human(n):
    for unit in ("B", "KB", "MB"):
        if n < 1024 or unit == "MB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{n} B"
        n /= 1024


def css_order():
    """从 worker/css.js 里读层叠顺序。

    刻意不在这里另抄一份：顺序即层叠顺序，两处各写一份，迟早走岔 ——
    到那时症状是 APP 里某几处样式被后面的盖掉，而网页版好好的，极难往这上面想。
    """
    src = io.open(CSS_JS, encoding="utf-8").read()
    m = re.search(r"const ORDER = \[(.*?)\];", src, re.S)
    if not m:
        raise SystemExit("！读不到 worker/css.js 的 ORDER —— 它改了结构，本脚本要跟着改")
    names = re.findall(r"'([a-z0-9-]+)'", m.group(1))
    if not names:
        raise SystemExit("！worker/css.js 的 ORDER 解析出来是空的")
    return names


def build_css():
    """按 ORDER 拼出 all.css，与 worker/css.js 的 build() 同法（join('\\n')）。"""
    parts = []
    for name in css_order():
        p = os.path.join(PUBLIC, "css", name + ".css")
        if not os.path.isfile(p):
            raise SystemExit(f"！缺 public/css/{name}.css，无法拼出 all.css")
        parts.append(io.open(p, encoding="utf-8").read())
    return "\n".join(parts)


def app_version():
    """从 app-android/app/build.gradle 取 versionName —— 版本号只此一处为准。"""
    try:
        src = io.open(GRADLE, encoding="utf-8").read()
        m = re.search(r'versionName\s+"([^"]+)"', src)
        return m.group(1) if m else ""
    except OSError:
        return ""


def walk(src):
    """列出目录下要拷的文件，返回 [(绝对路径, 相对 public 的路径)]。"""
    out = []
    for dirpath, dirnames, filenames in os.walk(src):
        dirnames[:] = sorted(d for d in dirnames if not d.startswith("."))
        for name in sorted(filenames):
            if name in SKIP_NAMES or name.startswith("."):
                continue
            p = os.path.join(dirpath, name)
            out.append((p, os.path.relpath(p, PUBLIC)))
    return out


def collect():
    """本次要打包的全部条目。"""
    items = []
    for name in FILES:
        p = os.path.join(PUBLIC, name)
        if not os.path.isfile(p):
            raise SystemExit(f"！缺 public/{name}")
        items.append((p, name))
    for d in DIRS:
        base = os.path.join(PUBLIC, d)
        if not os.path.isdir(base):
            raise SystemExit(f"！缺 public/{d}/")
        items += walk(base)
    return items


def main():
    check_only = "--check" in sys.argv

    items = collect()
    css = build_css()
    css_bytes = css.encode("utf-8")

    total = sum(os.path.getsize(p) for p, _ in items) + len(css_bytes)
    by_dir = {}
    for p, rel in items:
        top = rel.split(os.sep)[0] if os.sep in rel else "(根目录)"
        e = by_dir.setdefault(top, [0, 0])
        e[0] += 1
        e[1] += os.path.getsize(p)

    print("将打包进 APP 的内容：")
    for k in sorted(by_dir):
        n, size = by_dir[k]
        print(f"  {k:<14} {n:>5} 个   {human(size):>10}")
    print(f"  {'css/all.css':<14} {1:>5} 个   {human(len(css_bytes)):>10}   （由 worker/css.js 的 ORDER 现拼）")
    print(f"  {'合计':<14} {len(items) + 1:>5} 个   {human(total):>10}")

    ver = app_version()
    print(f"\n外壳版本（build.gradle 的 versionName）：{ver or '（读不到）'}")

    # 内容版本：正文与目录的聚合摘要。内容没变则版本号不变，便于比对
    agg = hashlib.sha256()
    agg.update(hashlib.sha256(css_bytes).hexdigest().encode())
    for p, rel in items:
        agg.update((rel.replace(os.sep, "/") + sha(p)).encode())
    content_ver = datetime.now(timezone.utc).strftime("%Y%m%d") + "-" + agg.hexdigest()[:8]
    print(f"内容版本：{content_ver}")

    if check_only:
        print("\n--check：未写入任何文件。")
        return

    # 整个重铺，不做增量：assets 是打包中转，留着上一轮的残余会让人查不清包里到底是什么
    if os.path.isdir(ASSETS):
        shutil.rmtree(ASSETS)
    os.makedirs(ASSETS)

    for p, rel in items:
        dst = os.path.join(ASSETS, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(p, dst)

    os.makedirs(os.path.join(ASSETS, "css"), exist_ok=True)
    with io.open(os.path.join(ASSETS, "css", "all.css"), "w", encoding="utf-8") as f:
        f.write(css)

    with io.open(os.path.join(ASSETS, "content-version.json"), "w", encoding="utf-8") as f:
        json.dump({
            "version": content_ver,
            "app": ver,
            "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "files": len(items) + 1,
        }, f, ensure_ascii=False, indent=2)

    # 线上发布信息：网页版据此显示下载按钮，APP 据此查有无新版。
    # 只此一处记版本与地址，避免和 build.gradle 走岔。
    os.makedirs(os.path.dirname(RELEASE_OUT), exist_ok=True)
    apk_name = f"foyue-{ver}.apk"
    apk_path = os.path.join(PUBLIC, "app", apk_name)
    release = {
        "version": ver,
        "url": f"/app/{apk_name}",
        "size": os.path.getsize(apk_path) if os.path.isfile(apk_path) else 0,
        "date": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
    }
    with io.open(RELEASE_OUT, "w", encoding="utf-8") as f:
        json.dump(release, f, ensure_ascii=False, indent=2)

    print(f"\n已写入 {os.path.relpath(ASSETS, ROOT)}/")
    print(f"已写入 {os.path.relpath(RELEASE_OUT, ROOT)}"
          + ("" if release["size"] else "   （size 为 0：APK 还没生成；签完包把它拷进 public/app/ 后再跑一次本脚本即可补上）"))
    print("\n下一步：cd app-android && ./gradlew :app:assembleRelease")


if __name__ == "__main__":
    main()
