#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""佛乐品牌资源生成：从标志原稿一次性产出站内所有图标与分享图。

原稿 brand/foyue-logo-source.png（2026-07-29 换新：敦煌飞天伎乐持宝瓶箜篌·
云纹圆章构图，米底满幅，图中不含字组）。字组 FOYUE.ORG 单独存
brand/foyue-wordmark.png（透明底，自上一版原稿抽出，排印延续不变）。产出：

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
from collections import deque

SRC   = "brand/foyue-logo-source.png"
WORD  = "brand/foyue-wordmark.png"
PAPER = (243, 236, 218)   # 宣纸，与 index.html 的 theme_color / manifest 同色，启动图无接缝

# 抠底阈值：按「离底色的色距」算，不能按亮度 —— 实测肤色 #fdeedb 与底色
# #fef7ed 的 max(RGB) 同为 253/254，亮度分不开；色距则肤≈20、云缝白线≈5。
D_CAND, D0, D1 = 12, 4, 16   # 色距 ≤12 视为疑似底色；alpha 渐变带 4→16


def key_bg_flood(img):
    """去米色底 —— 只抠「与画外相连通」的底色。

    新原稿与旧线稿不同：人物肤色、云纹留白与底色同为近白米色，若全局
    抠底会把脸和腹背一并抠穿（夜色底上成了空洞）。故先从四边种子做
    连通域泛洪，只有连到画外的米白才算底；被描线围合的米色（脸、手、
    身躯）原样保留。泛洪区内按色距出 alpha，抗锯齿边不发灰。"""
    a  = np.asarray(img.convert("RGB")).astype(np.float32)
    H, W = a.shape[:2]
    edge = np.concatenate([a[0], a[-1], a[:, 0], a[:, -1]])
    bg = np.median(edge, axis=0)            # 底色实测参考值
    dist = np.sqrt(((a - bg) ** 2).sum(axis=2))
    cand = dist <= D_CAND                   # 近到可能是底色的像素
    flood = np.zeros((H, W), dtype=bool)
    dq = deque()
    for x in range(W):
        for y in (0, H - 1):
            if cand[y, x] and not flood[y, x]: flood[y, x] = True; dq.append((y, x))
    for y in range(H):
        for x in (0, W - 1):
            if cand[y, x] and not flood[y, x]: flood[y, x] = True; dq.append((y, x))
    while dq:
        y, x = dq.popleft()
        if y > 0     and cand[y-1, x] and not flood[y-1, x]: flood[y-1, x] = True; dq.append((y-1, x))
        if y < H - 1 and cand[y+1, x] and not flood[y+1, x]: flood[y+1, x] = True; dq.append((y+1, x))
        if x > 0     and cand[y, x-1] and not flood[y, x-1]: flood[y, x-1] = True; dq.append((y, x-1))
        if x < W - 1 and cand[y, x+1] and not flood[y, x+1]: flood[y, x+1] = True; dq.append((y, x+1))
    # 泛洪区向内扩 2px，把底色贴着描线的抗锯齿过渡像素也交给渐变 alpha
    zone = flood.copy()
    for _ in range(2):
        z = zone.copy()
        z[1:, :] |= zone[:-1, :]; z[:-1, :] |= zone[1:, :]
        z[:, 1:] |= zone[:, :-1]; z[:, :-1] |= zone[:, 1:]
        zone = z
    al = np.full((H, W), 255.0)
    ramp = np.clip((dist - D0) / float(D1 - D0), 0, 1) * 255
    al[zone] = ramp[zone]
    al[zone & (al < 10)] = 0                # 抹掉底噪，免得留下半透明脏块
    return Image.fromarray(np.dstack([a, al]).astype(np.uint8), "RGBA")


def night_lift(rgba, lift=0.45):
    """夜烛版：抠底结果不动，只整体抬明度、保住色相与饱和 ——
    宣纸上沉稳的矿彩落到夜色里仍浮得起来，不至于陷成一团墨。"""
    a  = np.asarray(rgba).astype(np.float32)
    rgb, al = a[..., :3] / 255.0, a[..., 3:]
    mx, mn = rgb.max(axis=2), rgb.min(axis=2)
    L  = (mx + mn) / 2
    L2 = np.clip(L + (1 - L) * lift, 0, 1)
    scale = np.where(L > 1e-4, L2 / np.maximum(L, 1e-4), 1.0)[..., None]
    out = np.clip(rgb * scale, 0, 1) * 255
    return Image.fromarray(np.dstack([out, al]).astype(np.uint8), "RGBA")


def trim(im):
    return im.crop(im.split()[-1].getbbox())


def save_png(im, path, colors=200):
    """图用调色板压缩，必须关掉抖动 —— 抖动会把半透明边缘打成棋盘点。
    压缩前把近透明像素归零并统一置黑：量化器按 RGBA 聚类，若全透明像素
    还留着各自的米色 RGB，会跟低 alpha 边缘像素并进同一槽位，透明底就脏了。"""
    arr = np.asarray(im.convert("RGBA")).copy()
    arr[arr[..., 3] < 16] = 0
    im = Image.fromarray(arr, "RGBA")
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    im.quantize(colors=colors, method=Image.FASTOCTREE, dither=Image.Dither.NONE).save(path, optimize=True)
    print(f"{path:36s} {im.size}  {os.path.getsize(path)/1024:.0f}K")


def fit(art, width):
    return art.resize((width, round(art.height * width / art.width)), Image.LANCZOS)


def plate(size, art, ratio, bg=PAPER, center="mass"):
    """把图形放到一块纯色板上（ratio = 图形宽 / 板宽；过高时改按高度收）。

    center="mass" 按 alpha 质心居中 —— 圆章右侧飘带尾、下方云尾又细又轻，
    把外接框拉得偏大，按框居中会显得图形浮向左上（maskable 圆裁下尤其明显）；
    center="bbox" 按外接框居中，给图形+字组的对称构图用。"""
    W, H = size
    a = fit(art, int(W * ratio))
    if a.height > H * 0.92:
        a = fit(art, round(art.width * (H * 0.92) / art.height))
    if center == "mass":
        al = np.asarray(a)[..., 3].astype(np.float32)
        ys, xs = np.mgrid[0:a.height, 0:a.width]
        cy, cx = (ys * al).sum() / al.sum(), (xs * al).sum() / al.sum()
        px, py = round(W / 2 - cx), round(H / 2 - cy)
    else:
        px, py = (W - a.width) // 2, (H - a.height) // 2
    px = max(0, min(W - a.width, px)); py = max(0, min(H - a.height, py))
    cv = Image.new("RGBA", (W, H), (*bg, 255))
    cv.paste(a, (px, py), a)
    return cv


def compose_lock(mark, word):
    """图形 + FOYUE.ORG 字组（居中竖排，字组宽取图形的 0.78）。"""
    w  = int(mark.width * 0.78)
    wd = fit(word, w)
    gap = round(mark.width * 0.03)
    cv = Image.new("RGBA", (mark.width, mark.height + gap + wd.height), (0, 0, 0, 0))
    cv.paste(mark, (0, 0), mark)
    cv.paste(wd, ((mark.width - w) // 2, mark.height + gap), wd)
    return cv


def main():
    keyed = key_bg_flood(Image.open(SRC))
    mark  = trim(keyed)
    lock  = compose_lock(mark, Image.open(WORD).convert("RGBA"))

    save_png(fit(mark, 640), "public/img/logo-mark.png")
    save_png(fit(lock, 800), "public/img/logo-full.png")

    # 夜烛模式专用（CSS 按 data-theme="night" 换底图，只下其中一张）
    save_png(fit(night_lift(mark), 640), "public/img/logo-mark-night.png")

    save_png(plate((512, 512),  mark, 0.84), "public/icon-512.png")
    save_png(plate((192, 192),  mark, 0.84), "public/icon-192.png")
    save_png(plate((180, 180),  mark, 0.84), "public/img/apple-touch-icon.png")
    save_png(plate((96, 96),    mark, 0.92), "public/favicon.png", colors=128)
    save_png(plate((512, 512),  mark, 0.58), "public/img/icon-maskable-512.png")   # maskable 安全区
    save_png(plate((1200, 630), lock, 0.40, center="bbox"), "public/img/og-cover.png")

    # 自检：透明版四角必须全透，否则说明抠底阈值需要重调
    chk = np.asarray(Image.open("public/img/logo-mark.png").convert("RGBA"))
    corners = [chk[0, 0, 3], chk[0, -1, 3], chk[-1, 0, 3], chk[-1, -1, 3]]
    assert not any(corners), f"透明底不干净，四角 alpha = {corners}"
    # 自检：肤色区必须仍是实底，证明连通域没把人抠穿（坐标为 1254 原稿实测点）
    ka = np.asarray(keyed)
    for name, (x, y) in {"额头": (660, 400), "面颊": (680, 450), "腹部": (560, 700)}.items():
        assert ka[y, x, 3] == 255, f"{name}被抠穿，alpha = {ka[y, x, 3]}"
    print(f"四角 alpha 全 0，额头/面颊/腹部实底；mark 宽高比 {mark.width}/{mark.height}"
          f" ≈ {mark.width / mark.height:.3f}（CSS .lotus aspect-ratio 需同步）")


if __name__ == "__main__":
    main()
