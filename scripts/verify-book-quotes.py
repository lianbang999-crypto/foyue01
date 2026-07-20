#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
《极乐世界庄严辑要》引文校验器（铁律工具：引文一字不改）。

对书稿 md 中每个引文块（连续 '>' 行），按（……）切段，逐段核对
是否逐字存在于〔出处〕所指底本：
  - 〔CBETA Txxnxxxx·段a–b〕 → 极乐世界资料库/经典原文/ 同名文件段落
  - 〔系列·第NN讲·段a–b〕   → data/daan_excerpts.json 对应条目
比对时仅忽略空白符；标点、字形均须一致。

用法：python3 scripts/verify-book-quotes.py 极乐世界资料库/书稿/品08-*.md
"""
import re, os, sys, glob, json

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIB = os.path.join(ROOT, '极乐世界资料库')

def paras_of(wid):
    fp = glob.glob(os.path.join(LIB, '经典原文', wid + '-*.txt'))[0]
    lines = open(fp, encoding='utf-8').read().split('\n')
    return [l.strip() for l in lines[3:] if l.strip()]

def norm(s):
    return re.sub(r'\s', '', s)

def verify(path, daan):
    text = open(path, encoding='utf-8').read()
    lines = text.split('\n')
    blocks, i = [], 0
    while i < len(lines):
        if lines[i].startswith('>'):
            j, buf = i, []
            while j < len(lines) and (lines[j].startswith('>') or not lines[j].strip()):
                if lines[j].startswith('>'):
                    buf.append(lines[j].lstrip('> ').strip())
                j += 1
            src = None
            for k in range(j, min(j + 3, len(lines))):
                m = re.search(r'〔(.+?)〕', lines[k])
                if m:
                    src = m.group(1)
                    break
            blocks.append(('\n'.join(buf), src))
            i = j + 1
        else:
            i += 1

    fails = 0
    for qtext, src in blocks:
        if not src:
            print(f'!! 无出处引文块: {qtext[:30]}…')
            fails += 1
            continue
        m = re.match(r'CBETA (T\d+n\d+[A-Za-z]?)·段(\d+)(?:–(\d+))?', src)
        if m:
            wid, a = m.group(1), int(m.group(2))
            b = int(m.group(3) or m.group(2))
            source = norm(''.join(paras_of(wid)[a - 1:b]))
        else:
            m2 = re.match(r'(.+?)·(.+?讲)·段(\d+)(?:–(\d+))?', src)
            if not m2:
                print(f'!! 出处无法解析: {src}')
                fails += 1
                continue
            skey, ch = norm(m2.group(1)), norm(m2.group(2))
            pool = [e for e in daan
                    if skey in norm(e['seriesTitle'] + e['chapterTitle'])
                    and ch in norm(e['chapterTitle'])]
            source = norm(''.join(e['text'] for e in pool))
            if not source:
                print(f'!! 出处未找到底本: {src}')
                fails += 1
                continue
        for seg in re.split(r'（……）', norm(qtext)):
            if seg and seg not in source:
                fails += 1
                print(f'✗ 未匹配〔{src}〕: {seg[:44]}…')
    return len(blocks), fails

if __name__ == '__main__':
    daan = json.load(open(os.path.join(LIB, 'data', 'daan_excerpts.json')))['items']
    total_b = total_f = 0
    for pat in sys.argv[1:]:
        for path in sorted(glob.glob(pat)):
            nb, nf = verify(path, daan)
            total_b += nb
            total_f += nf
            print(f'{os.path.basename(path)}: 引文块 {nb}，失败段 {nf}')
    if total_f:
        sys.exit(1)
    print(f'全部通过：{total_b} 个引文块逐字与底本一致')
