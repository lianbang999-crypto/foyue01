#!/usr/bin/env python3
# 文库构建管线：把「大安法师（讲法集）TXT」的混合格式文本（docx/doc/GBK-txt）
# 统一转为 UTF-8 纯文本，输出到 public/text/，并生成目录索引 public/library.json
#
# 依赖 macOS 自带 textutil（doc/docx 转换）
# 运行：python3 scripts/build-library.py

import json, os, re, subprocess, sys, unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / '大安法师（讲法集）TXT'
OUT_TEXT = ROOT / 'public' / 'text'
OUT_INDEX = ROOT / 'public' / 'library.json'

SKIP_NAMES = {'.DS_Store', '问答来源.docx'}


def read_any(path: Path) -> str | None:
    """按格式读取为 UTF-8 文本；失败返回 None"""
    suf = path.suffix.lower()
    if suf in ('.docx', '.doc'):
        r = subprocess.run(['textutil', '-convert', 'txt', '-stdout', str(path)],
                           capture_output=True, timeout=120)
        if r.returncode != 0 or not r.stdout:
            return None
        return r.stdout.decode('utf-8', 'ignore')
    if suf == '.txt':
        raw = path.read_bytes()
        for enc in ('utf-8-sig', 'utf-8', 'gb18030', 'big5'):
            try:
                return raw.decode(enc)
            except UnicodeDecodeError:
                continue
        return raw.decode('gb18030', 'ignore')
    return None


def clean_text(t: str) -> str:
    """规整化：统一换行、去页码残渣、去多余空行"""
    t = t.replace('\r\n', '\n').replace('\r', '\n')
    t = unicodedata.normalize('NFC', t)
    lines = []
    for line in t.split('\n'):
        s = line.strip()
        if re.fullmatch(r'\d{1,3}', s):  # 独行页码
            continue
        lines.append(s)
    # 折叠 3+ 连续空行
    out, blank = [], 0
    for s in lines:
        if not s:
            blank += 1
            if blank > 1:
                continue
        else:
            blank = 0
        out.append(s)
    return '\n'.join(out).strip()


def ep_num(name: str):
    m = re.search(r'第\s*(\d+)\s*[讲集辑首]', name)
    return int(m.group(1)) if m else None


series_index = []
qa_index = []
warnings = []
written = 0

if OUT_TEXT.exists():
    import shutil
    shutil.rmtree(OUT_TEXT)
OUT_TEXT.mkdir(parents=True)

# ---- 讲法系列（编号文件夹）----
for d in sorted(SRC.iterdir()):
    if not d.is_dir():
        continue
    m = re.match(r'^(\d+)\s+(.+?)\s+\d+[讲辑]$', d.name) or re.match(r'^(\d+)\s+(.+)$', d.name)
    if not m:
        continue  # 800问 等另行处理
    if d.name == '大安法师800问':
        continue
    num, title = int(m.group(1)), m.group(2).strip()
    sid = f's{num:02d}'
    chapters = []
    files = [f for f in sorted(d.iterdir())
             if f.is_file() and f.name not in SKIP_NAMES
             and not f.name.startswith('~$') and not f.name.endswith('.downloading')]
    # 同名 txt/doc 并存时优先 txt（仅按完整文件名去重，不能按讲次去重——
    # 如《无量寿经要义》分卷上/卷下，各有 第01讲，讲次号会重复）
    by_stem = {}
    for f in files:
        stem = f.stem
        if stem not in by_stem or f.suffix.lower() == '.txt':
            by_stem[stem] = f

    def natural_key(stem):
        # 数字零填充做自然排序（"第2讲" < "第10讲"；"卷上" < "卷下" 恰合码点序）
        return re.sub(r'\d+', lambda m: m.group(0).zfill(4), stem)

    ordered = sorted(by_stem.items(), key=lambda kv: natural_key(kv[0]))
    for k, f in ordered:
        text = read_any(f)
        if not text or len(text.strip()) < 50:
            warnings.append(f'转换失败或过短: {f.relative_to(SRC)}')
            continue
        text = clean_text(text)
        n = len(chapters) + 1
        rel = f'{sid}/{n:02d}.txt'
        (OUT_TEXT / sid).mkdir(exist_ok=True)
        (OUT_TEXT / sid / f'{n:02d}.txt').write_text(text, 'utf-8')
        ctitle = f.stem
        ctitle = re.sub(r'^\d+[\s.、]*', '', ctitle)  # 去前缀序号
        chapters.append({'n': n, 'title': ctitle, 'path': rel, 'chars': len(text)})
        written += 1
    if chapters:
        series_index.append({
            'id': sid, 'num': num, 'title': title,
            'count': len(chapters), 'chapters': chapters,
        })
    else:
        warnings.append(f'系列无有效文本: {d.name}')

# ---- 单篇开示（散落根目录的 txt）----
loose = []
for f in sorted(SRC.iterdir()):
    if f.is_file() and f.suffix.lower() == '.txt' and f.name not in SKIP_NAMES:
        text = read_any(f)
        if not text or len(text.strip()) < 50:
            warnings.append(f'单篇转换失败: {f.name}')
            continue
        text = clean_text(text)
        n = len(loose) + 1
        rel = f'loose/{n:02d}.txt'
        (OUT_TEXT / 'loose').mkdir(exist_ok=True)
        (OUT_TEXT / 'loose' / f'{n:02d}.txt').write_text(text, 'utf-8')
        title = re.sub(r'^大安法师[:：]\s*', '', f.stem)
        loose.append({'n': n, 'title': title, 'path': rel, 'chars': len(text)})
        written += 1
if loose:
    series_index.append({'id': 'loose', 'num': 99, 'title': '单篇开示', 'count': len(loose), 'chapters': loose})

# ---- 大安法师800问 ----
qa_dir = SRC / '大安法师800问'
(OUT_TEXT / 'qa').mkdir(exist_ok=True)
qa_files = []
for sub in sorted(qa_dir.iterdir()):
    if sub.is_dir():
        for f in sorted(sub.iterdir()):
            if (f.is_file() and f.name not in SKIP_NAMES and not f.name.startswith('~$')
                    and not f.name.endswith('.downloading')):
                qa_files.append(f)

seen_titles = set()
for f in qa_files:
    stem = f.stem
    m = re.match(r'^(\d+)\s*(.*)$', stem)
    num = int(m.group(1)) if m else None
    title = (m.group(2) if m else stem).strip() or stem
    norm = re.sub(r'[\s？?。．.，,！!“”"\'（）()]', '', title)
    if norm in seen_titles:
        continue  # 跨文件夹重复问题去重
    text = read_any(f)
    if not text or len(text.strip()) < 30:
        warnings.append(f'800问转换失败: {f.relative_to(SRC)}')
        continue
    text = clean_text(text)
    seen_titles.add(norm)
    qid = len(qa_index) + 1
    rel = f'qa/{qid:03d}.txt'
    (OUT_TEXT / 'qa' / f'{qid:03d}.txt').write_text(text, 'utf-8')
    qa_index.append({'n': qid, 'title': title, 'path': rel})
    written += 1

library = {
    'generatedAt': __import__('datetime').date.today().isoformat(),
    'seriesCount': len(series_index),
    'chapterCount': sum(s['count'] for s in series_index),
    'qaCount': len(qa_index),
    'series': series_index,
    'qa': qa_index,
}
OUT_INDEX.write_text(json.dumps(library, ensure_ascii=False, indent=1), 'utf-8')

total_mb = sum(f.stat().st_size for f in OUT_TEXT.rglob('*.txt')) / 1e6
print(f'✓ 文库构建完成：{len(series_index)} 个系列 / {library["chapterCount"]} 章 + 问答 {len(qa_index)} 条，'
      f'共写出 {written} 个文件（{total_mb:.1f} MB）')
if warnings:
    print(f'\n⚠ 警告 {len(warnings)} 条：')
    for w in warnings[:30]:
        print('  ' + w)
