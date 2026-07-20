#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""佛乐品牌资源生成：从标志原稿一次性产出站内所有图标与分享图。

原稿 brand/foyue-logo-source.png（米底版）里，「飞天伎乐持排箫 + 莲花出水」
是标志图形，下方 FOYUE.ORG 是字组。本脚本把两者分别抠出，产出：

  public/img/logo-mark.png          透明底 · 图形（顶栏 / 播放器莲台 / 底部停靠条）
  public/img/logo-full.png          透明底 · 图形 + 字组（结构化数据 logo）
  public/icon-512.png               宣纸底方图（PWA / 锁屏封面）
  public/icon-192.png               宣纸底方图（PWA / 锁屏封面小号）
  public/img/apple-touch-icon.png   iOS 添加到主屏
  public/img/icon-maskable-512.png  Android 自适应图标（图形内收进安全区）
  public/favicon.png                浏览器页签
  public/img/og-cover.png           1200×630 分享封面（微信 / X / OG）

用法：python3 scripts/build-brand-assets.py
依赖：pillow numpy
"""

from PIL import Image
import numpy as np
import os

SRC   = "brand/foyue-logo-source.png"
PAPER = (243, 236, 218)   # 宣纸，与 index.html 的 theme_color / manifest 同色，启动图无接缝

# 原稿里图形与字组的行分界（1254×1254 原图坐标）
BOX_MARK = (0, 150, 1254, 840)    # 图形
BOX_LOCK = (0, 150, 1254, 1010)   # 图形 + FOYUE.ORG


def key_bg(img, opaque=226, clear=250):
    """去米色底：保留原色，按「离底色的深浅」生成 alpha，抗锯齿边缘不发灰。"""
    a  = np.asarray(img.convert("RGB")).astype(np.float32)
    mx = a.max(axis=2)
    al = np.clip((clear - mx) / float(clear - opaque), 0, 1) * 255
    al[al < 10] = 0                     # 抹掉底噪，免得留下半透明脏块
    return Image.fromarray(np.dstack([a, al]).astype(np.uint8), "RGBA")


def key_bg_night(img, opaque=226, clear=250, lift=0.50):
    """夜间版：抠底阈值必须与日间一致 —— 收窄阈值会把抗锯齿边缘一并吃成实线，
    描线变肥发糙，成了粉笔画。夜版只做一件事：整体抬明度、保住色相与饱和，
    让在宣纸上本就浅淡的身姿衣纹，落到夜色里仍浮得起来。"""
    a  = np.asarray(img.convert("RGB")).astype(np.float32) / 255.0
    mx = a.max(axis=2)
    al = np.clip((clear/255.0 - mx) / ((clear - opaque)/255.0), 0, 1)
    al[al < 0.04] = 0
    L  = (mx + a.min(axis=2)) / 2                       # HSL 明度
    L2 = np.clip(L + (1 - L) * lift, 0, 1)
    scale = np.where(L > 1e-4, L2 / np.maximum(L, 1e-4), 1.0)[..., None]
    out = np.clip(a * scale, 0, 1)
    return Image.fromarray(np.dstack([out * 255, al * 255]).astype(np.uint8), "RGBA")


def trim(im):
    return im.crop(im.split()[-1].getbbox())


def save_png(im, path, colors=200):
    """线稿图用调色板压缩，必须关掉抖动 —— 抖动会把半透明边缘打成棋盘点。"""
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    im.quantize(colors=colors, method=Image.FASTOCTREE, dither=Image.Dither.NONE).save(path, optimize=True)
    print(f"{path:36s} {im.size}  {os.path.getsize(path)/1024:.0f}K")


def fit(art, width):
    return art.resize((width, round(art.height * width / art.width)), Image.LANCZOS)


def plate(size, art, ratio, bg=PAPER):
    """把图形居中放到一块纯色板上（ratio = 图形宽 / 板宽）。"""
    W, H = size
    w = int(W * ratio)
    a = fit(art, w)
    cv = Image.new("RGBA", (W, H), (*bg, 255))
    cv.paste(a, ((W - w) // 2, (H - a.height) // 2), a)
    return cv


def main():
    src  = Image.open(SRC)
    full = key_bg(src)
    mark = trim(full.crop(BOX_MARK))
    lock = trim(full.crop(BOX_LOCK))

    save_png(fit(mark, 640), "public/img/logo-mark.png")
    save_png(fit(lock, 800), "public/img/logo-full.png")

    # 夜烛模式专用（CSS 按 data-theme="night" 换底图，只下其中一张）
    night = trim(key_bg_night(src).crop(BOX_MARK))
    save_png(fit(night, 640), "public/img/logo-mark-night.png")

    save_png(plate((512, 512),  mark, 0.80), "public/icon-512.png")
    save_png(plate((192, 192),  mark, 0.80), "public/icon-192.png")
    save_png(plate((180, 180),  mark, 0.80), "public/img/apple-touch-icon.png")
    save_png(plate((96, 96),    mark, 0.90), "public/favicon.png", colors=128)
    save_png(plate((512, 512),  mark, 0.58), "public/img/icon-maskable-512.png")   # maskable 安全区
    save_png(plate((1200, 630), lock, 0.52), "public/img/og-cover.png")

    # 自检：透明版四角必须全透，否则说明抠底阈值需要重调
    chk = np.asarray(Image.open("public/img/logo-mark.png").convert("RGBA"))
    corners = [chk[0, 0, 3], chk[0, -1, 3], chk[-1, 0, 3], chk[-1, -1, 3]]
    assert not any(corners), f"透明底不干净，四角 alpha = {corners}"
    print("四角 alpha 全 0，透明底干净。")


if __name__ == "__main__":
    main()
