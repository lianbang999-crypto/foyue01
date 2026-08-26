#!/usr/bin/env python3
"""
把 Looka Core Icon System 的源 SVG 转成 Android VectorDrawable。

**产物是机器生成的，不要手改 `res/drawable/ic_lk_*.xml`** ——
改了下次跑这个脚本就没了。要改就改源 SVG（`icon组件素材参考/workspace/output/icons/`）。

源规格：24×24 网格 · 1.8px 描边 · round caps & joins · currentColor。
转换时描边色一律写成 #FF000000，实际颜色由 Compose 的 `Icon(tint = …)` 覆盖 ——
**不要在 vector 里写 `android:tint="?attr/…"`**，那是 View 体系的主题属性，
在 Compose 里解析不到，白忙一场。

用法：python3 scripts/build_icons.py
"""
import glob
import os
import re

SRC = "icon组件素材参考/workspace/output/icons"
OUT = "app/src/main/res/drawable"

HEAD = """<?xml version="1.0" encoding="utf-8"?>
<!-- Looka Core Icon System：24×24 / 1.8px 描边 / round caps。
     {origin}
     **机器生成，不要手改** —— 改源 SVG 后跑 scripts/build_icons.py。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
"""

STROKE = ('    <path android:pathData="{d}"\n'
          '        android:strokeWidth="{w}" android:strokeColor="{c}"\n'
          '        android:strokeLineCap="round" android:strokeLineJoin="round"{extra}/>\n')
FILL = '    <path android:pathData="{d}" android:fillColor="{c}"/>\n'


def circle_path(cx, cy, r):
    return f"M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0"


def convert(svg_text):
    """把一个 SVG 的绘图元素转成 VectorDrawable 的 path 列表"""
    out = ""
    for m in re.finditer(r"<path\b([^>]*?)/?>", svg_text):
        a = m.group(1)
        d = re.search(r'\bd="([^"]+)"', a)
        if not d:
            continue
        fm = re.search(r'\bfill="([^"]+)"', a)
        extra = ' android:fillColor="#FF000000"' if fm and fm.group(1) not in ("none", "") else ""
        out += STROKE.format(d=d.group(1), w="1.8", c="#FF000000", extra=extra)
    for m in re.finditer(r"<circle\b([^>]*?)/?>", svg_text):
        a = m.group(1)
        g = lambda k: re.search(rf'\b{k}="([\d.]+)"', a)
        if g("cx") and g("cy") and g("r"):
            out += STROKE.format(
                d=circle_path(float(g("cx").group(1)), float(g("cy").group(1)), float(g("r").group(1))),
                w="1.8", c="#FF000000", extra="")
    for m in re.finditer(r"<rect\b([^>]*?)/?>", svg_text):
        a = m.group(1)
        g = lambda k: float(re.search(rf'\b{k}="([\d.]+)"', a).group(1)) if re.search(rf'\b{k}="([\d.]+)"', a) else 0
        x, y, w, h = g("x"), g("y"), g("width"), g("height")
        if w and h:
            out += STROKE.format(d=f"M{x},{y} h{w} v{h} h{-w} Z", w="1.8", c="#FF000000", extra="")
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    made = []
    star_svg = None
    for f in sorted(glob.glob(SRC + "/*.svg")):
        name = os.path.basename(f)[:-4]
        text = open(f).read()
        if name == "star":
            star_svg = text
        paths = convert(text)
        if not paths:
            print("跳过（无可转元素）:", name)
            continue
        open(f"{OUT}/ic_lk_{name}.xml", "w").write(
            HEAD.format(origin=f"源：{SRC}/{name}.svg") + paths + "</vector>\n")
        made.append(name)

    # ── 派生图标 ────────────────────────────────────────────────
    # 这三个**不在原始 24 个里**，是按同一套笔法（1.8px / round cap）补的。
    # 为什么补：待办行的「圆圈 / 打勾 / 星标」是全站看得最多的像素，
    # 只换了空心星、实心星还留着 Material 的，一行里两种笔法最难看。
    derived = {
        # 空心圆（未完成）
        "circle": STROKE.format(d=circle_path(12, 12, 9), w="1.8", c="#FF000000", extra=""),
        # 实心圆 + 白勾（已完成）
        "check_circle": (
            FILL.format(d=circle_path(12, 12, 10), c="#FF000000")
            + STROKE.format(d="M7.8,12.3 L10.6,15.1 L16.2,9.2", w="2", c="#FFFFFFFF", extra="")
        ),
        # U 形回弯箭头：日历「回到今天」浮动按钮。
        # 照实机图 114 那颗按钮里的字形描的 —— 箭头在左上，横线向右，
        # 右侧半圆回弯，底部再向左收。不用 Material 的 Undo 是因为那个笔法
        # 与这套 1.8px 描边不是一家，同屏出现会打架。
        "return_today": (
            STROKE.format(d="M10.5,5 L6.5,9 L10.5,13", w="1.8", c="#FF000000", extra="")
            + STROKE.format(d="M6.5,9 H14 A4,4 0 0 1 14,17 H9", w="1.8", c="#FF000000", extra="")
        ),
    }
    if star_svg:
        # 实心星：与空心星**同一条轮廓**，只是填上 —— 保证两态形状完全一致
        d = re.search(r'\bd="([^"]+)"', star_svg)
        if d:
            derived["star_fill"] = FILL.format(d=d.group(1), c="#FF000000")
    for name, paths in derived.items():
        open(f"{OUT}/ic_lk_{name}.xml", "w").write(
            HEAD.format(origin="派生图标（非原始 24 个，按同一笔法补）") + paths + "</vector>\n")
        made.append(name + "*")

    print(f"生成 {len(made)} 个（带 * 的是派生）：{' '.join(made)}")


if __name__ == "__main__":
    main()
