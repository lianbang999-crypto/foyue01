#!/usr/bin/env python3
"""经典原文构建：《净土五经》（印光大师校订本）docx → 章节纯文本。

与讲记（build-library.py 的主管线）根本不同的一点：**经文逐字保真**。
讲记那边会去独行页码、折叠空行、剥文件名序号；这里一概不做——
经文原文不容任何删改，脚本只干两件事：从 docx XML 取出文本、按标题切章。
唯一的规整是去掉每段首尾空白（源文档的缩进），以及丢弃全空段落。

不引第三方依赖：docx 就是个 zip，w:p 段落、w:pStyle 样式、w:t 文本，
标准库 zipfile + ElementTree 足够，且已核对过与 python-docx 结果逐字相同
（737 段 / 50826 字）。这样别人 clone 下来不装任何东西就能重建文库。

被本脚本调用的入口是 build()，由 build-library.py 在主管线中调用。
单独运行可做自检：python3 scripts/build_sutra.py
"""

import re
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = ROOT / '净土五经（印光大师校订本）'
SRC_DOCX = SRC_DIR / '《净土五经》（简体校对本）印光法师校订版.docx'

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

SERIES_ID = 'jing'
SERIES_TITLE = '净土五经（印光大师校订本）'

# 源文档的字符数（去每段首尾空白后求和）。转换后须精确相等——
# 这是「一个字都没丢、没多」的硬校验，改动脚本后跑一次自检即可确认。
EXPECT_CHARS = 50826
EXPECT_PARAS = 737

# 卷首（第一个标题之前）自成一章，承载版本溯源：
# 「根据苏州弘化社民国二十二年，印光大师校印流通的《净土五经》原影印本校对，
#   句读一一依照原书」——这段说明必须随经文一起留在站上。
FRONT_TITLE = '校印说明'

# 排版把三处标题折成了两行：源文档中表现为连续两个标题、中间无正文。
# 脚本按此特征自动合并（直接相连，因为本就是一个标题被折行），前两处
# 合并后正好等于该章正文末尾的完整经名：
#   #357 大佛顶首楞严经大势至菩萨念佛圆通章
#   #500 大方广佛华严经普贤菩萨行愿品
# 第三处「大佛顶首楞严经卷第六」+「四种决定清净明诲」是出处 + 篇名两截，
# 直接相连读着挤，且正文末尾作「大佛顶首楞严经四种决定清净明诲（终）」，
# 故只对它一条做显示上的补空格。正文一字未动。
TITLE_FIX = {
    '大佛顶首楞严经卷第六四种决定清净明诲': '大佛顶首楞严经卷第六 四种决定清净明诲',
}


def _paragraphs(docx_path: Path):
    """→ [(style_id, text)]，按文档顺序。style_id 为 '1'/'2' 表示标题层级。"""
    with zipfile.ZipFile(docx_path) as z:
        xml = z.read('word/document.xml')
    body = ET.fromstring(xml).find(f'{W}body')
    out = []
    for p in body.findall(f'{W}p'):
        pPr = p.find(f'{W}pPr')
        style = ''
        if pPr is not None:
            st = pPr.find(f'{W}pStyle')
            if st is not None:
                style = st.get(f'{W}val') or ''
        # 按文档顺序收集文本；软回车/制表符还原为空白，图片等非文本节点自然略过
        buf = []
        for node in p.iter():
            if node.tag == f'{W}t':
                buf.append(node.text or '')
            elif node.tag == f'{W}br':
                buf.append('\n')
            elif node.tag == f'{W}tab':
                buf.append('\t')
        out.append((style, ''.join(buf)))
    return out


def _split_chapters(paras):
    """按一级标题切章；合并被排版折行的标题。→ [(title, [正文段落])]"""
    # 一级标题的下标
    h1 = [i for i, (s, t) in enumerate(paras) if s == '1' and t.strip()]

    # 连续两个一级标题之间若无任何非空正文，说明是同一个标题被折行 → 并为一个
    merged = []          # [(标题下标, 合并后的标题, 正文起始下标)]
    k = 0
    while k < len(h1):
        i = h1[k]
        title = paras[i][1].strip()
        j = k
        while j + 1 < len(h1):
            nxt = h1[j + 1]
            between = [paras[x][1].strip() for x in range(h1[j] + 1, nxt)]
            if any(between):
                break
            title += paras[nxt][1].strip()
            j += 1
        merged.append((i, TITLE_FIX.get(title, title), h1[j] + 1))
        k = j + 1

    chapters = []
    # 卷首：第一个标题之前的内容
    if merged:
        front = [t.strip() for _, t in paras[:merged[0][0]] if t.strip()]
        if front:
            chapters.append((FRONT_TITLE, front))

    for idx, (_, title, start) in enumerate(merged):
        end = merged[idx + 1][0] if idx + 1 < len(merged) else len(paras)
        lines = [t.strip() for _, t in paras[start:end] if t.strip()]
        chapters.append((title, lines))
    return chapters


def build(out_text_dir: Path, verbose: bool = True):
    """转换并写出章节文本。→ library.json 用的 series 字典；源缺失则返回 None。"""
    if not SRC_DOCX.exists():
        if verbose:
            print(f'⚠ 未找到经典原文源文件，跳过：{SRC_DOCX.relative_to(ROOT)}')
        return None

    paras = _paragraphs(SRC_DOCX)
    chapters = _split_chapters(paras)

    dest = out_text_dir / SERIES_ID
    dest.mkdir(parents=True, exist_ok=True)

    index, out_lines = [], []
    for n, (title, lines) in enumerate(chapters, 1):
        # 首行放标题：txt 单独打开也完整，阅读器则会自动跳过与标题重复的首行
        text = '\n'.join([title] + lines)
        (dest / f'{n:02d}.txt').write_text(text, 'utf-8')
        index.append({'n': n, 'title': title, 'path': f'{SERIES_ID}/{n:02d}.txt',
                      'chars': len(''.join(lines))})
        # 供校验用的「实际写出」序列：卷首那一章的标题是本脚本加的导航名，
        # 源文档里没有这四个字，比对时不能算进去
        if not (n == 1 and title == FRONT_TITLE):
            out_lines.append(title)
        out_lines.extend(lines)

    # —— 保真校验：把源文档与写出内容各自抹去空白后逐字比对 ——
    # 抹空白是因为两处显示层调整（TITLE_FIX 补的一个空格、折行标题合并）
    # 只动空白、不动一个字；抹掉之后两串必须完全相同，少一字或多一字都会暴露。
    strip_ws = lambda s: re.sub(r'\s+', '', s)
    src_all = strip_ws(''.join(t for _, t in paras))
    out_all = strip_ws(''.join(out_lines))
    src_chars = sum(len(t.strip()) for _, t in paras)
    body_chars = sum(c['chars'] for c in index)

    same = src_all == out_all
    counts_ok = src_chars == EXPECT_CHARS and len(paras) == EXPECT_PARAS
    if verbose:
        print(f'  经典原文：{len(chapters)} 章 / 正文 {body_chars} 字')
        print(f'  保真校验：源 {src_chars} 字 {len(paras)} 段；'
              f'抹空白后 源 {len(src_all)} 字 vs 写出 {len(out_all)} 字 '
              f'{"✅ 逐字相符" if same else "❌ 不符"}')
    if not same:
        # 定位首个分歧，便于排查
        i = next((k for k in range(min(len(src_all), len(out_all)))
                  if src_all[k] != out_all[k]), min(len(src_all), len(out_all)))
        raise SystemExit(
            f'经文保真校验失败：第 {i} 字起分歧\n'
            f'  源  …{src_all[max(0, i - 20):i + 20]}…\n'
            f'  写出…{out_all[max(0, i - 20):i + 20]}…\n'
            f'经典原文不容差异，已中止构建。')
    if not counts_ok:
        raise SystemExit(
            f'源文档与预期不符：{src_chars} 字 / {len(paras)} 段，'
            f'预期 {EXPECT_CHARS} / {EXPECT_PARAS}。'
            f'若确为换用了新版底本，请核对无误后更新脚本顶部的预期值。')

    return {'id': SERIES_ID, 'num': 0, 'title': SERIES_TITLE,
            'count': len(index), 'chapters': index}


if __name__ == '__main__':
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        s = build(Path(tmp))
        if s:
            print(f'\n《{s["title"]}》共 {s["count"]} 章：')
            for c in s['chapters']:
                print(f'  {c["n"]:>2}. {c["chars"]:>6} 字  {c["title"]}')
