#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
提取大安法师讲集中所有描述西方极乐世界（依报庄严/正报庄严）的段落。

方法：段落级关键词评分提取（逐字保留原文，不改写、不生成）。
  - S1 高精度场景词（净土场景专有，命中即取，权重3）
  - S2 一般场景词（需要叠加佐证，权重1）
  - CTX 语境词（极乐/西方/净土等，权重1，封顶2）
  - 连续命中的相邻段落合并为一条摘录（经文引文+讲解常跨段）
输出：极乐世界资料库/data/daan_excerpts.json

用法：python3 scripts/extract-jile-scenes.py
"""
import json, os, re, sys
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEXT = os.path.join(ROOT, 'public', 'text')
OUT_DIR = os.path.join(ROOT, '极乐世界资料库', 'data')

# ---------- 词典（主题分类 → 关键词） ----------
# 每个词条: (词, 级别)  级别: 1=S1高精度  2=S2一般
LEXICON = {
    '宝地平整': [
        ('黄金为地', 1), ('金沙布地', 1), ('琉璃为地', 1), ('七宝为地', 1),
        ('地平如掌', 1), ('琉璃地', 1), ('宝地', 2), ('金地', 2),
    ],
    '栏楯罗网行树': [
        ('七重栏楯', 1), ('七重罗网', 1), ('七重行树', 1),
        ('栏楯', 2), ('罗网', 2), ('行树', 2), ('宝网', 2),
    ],
    '宝树': [
        ('道场树', 1), ('宝树', 1), ('菩提树', 2), ('七宝树', 1),
        ('树观', 2), ('栴檀', 2), ('华果', 2),
    ],
    '莲池德水': [
        ('七宝池', 1), ('八功德水', 1), ('功德水', 1), ('宝池', 2), ('莲池', 2),
        ('泉池', 2), ('浴池', 2), ('德水', 2), ('池水', 2), ('金沙', 2), ('阶道', 2),
    ],
    '莲华': [
        ('莲华化生', 1), ('微妙香洁', 1), ('大如车轮', 1), ('莲华', 2), ('莲花', 2),
        ('莲台', 2), ('华座', 2), ('九品', 2), ('华开见佛', 1), ('莲胎', 1), ('青色青光', 1),
    ],
    '楼阁宫殿': [
        ('楼阁', 2), ('宫殿', 2), ('讲堂', 2), ('精舍', 2), ('宝殿', 2), ('阁楼', 2),
    ],
    '虚空天乐华雨': [
        ('天雨曼陀罗', 1), ('曼陀罗华', 1), ('昼夜六时', 1), ('天乐', 2), ('天华', 2),
        ('华雨', 2), ('幢幡', 2), ('宝盖', 2), ('璎珞', 2), ('音乐', 2), ('虚空', 2),
    ],
    '化鸟演法': [
        ('迦陵频伽', 1), ('共命之鸟', 1), ('共命鸟', 1), ('杂色之鸟', 1),
        ('白鹤', 2), ('孔雀', 2), ('鹦鹉', 2), ('众鸟', 2), ('化鸟', 1), ('出和雅音', 1),
    ],
    '德风光明': [
        ('德风', 1), ('无量光', 2), ('金光', 2), ('光明', 2), ('光色', 2),
        ('微风吹动', 1), ('微风', 2), ('晃曜', 2), ('宝香', 2),
    ],
    '衣食受用': [
        ('思衣得衣', 1), ('思食得食', 1), ('百味饮食', 1), ('自然化生', 1),
        ('衣服饮食', 2), ('应念而至', 2), ('七宝钵器', 1),
    ],
    '佛身相好': [
        ('八万四千相', 1), ('三十二相', 2), ('紫磨真金', 1), ('白毫', 1), ('绀目', 1),
        ('相好', 2), ('金色身', 2), ('丈六', 2), ('化佛', 2), ('眉间白毫', 1),
        ('身高六十万亿', 1), ('毫相', 2),
    ],
    '圣众天人': [
        ('诸上善人', 1), ('清虚之身', 1), ('无极之体', 1), ('声闻', 2), ('天人', 2),
        ('那罗延', 2), ('一生补处', 2), ('阿鞞跋致', 2), ('阿惟越致', 2),
    ],
    '十六观境': [
        ('十六观', 1), ('落日悬鼓', 1), ('日观', 2), ('水观', 2), ('地观', 2),
        ('像观', 2), ('真身观', 2), ('观想', 2), ('悬鼓', 1),
    ],
    '七宝众宝': [
        ('七宝', 2), ('琉璃', 2), ('玛瑙', 2), ('砗磲', 2), ('车渠', 2),
        ('赤珠', 2), ('玻璃', 2), ('颇梨', 2), ('珊瑚', 2), ('琥珀', 2), ('摩尼', 2),
    ],
    '临终接引': [
        ('圣众现前', 1), ('紫金台', 1), ('异香满室', 1), ('天乐盈空', 1),
        ('接引', 2), ('金台', 2), ('银台', 2), ('莲台', 2), ('垂手', 2),
        ('金色臂', 1), ('接引导师', 1), ('临终在定之心', 1),
    ],
    '边地疑城': [
        ('疑城', 1), ('胎宫', 1), ('七宝宫殿', 1), ('边地', 2), ('胎生', 2),
        ('五百岁', 2),
    ],
    '世界特性': [
        ('无有众苦', 1), ('但受诸乐', 1), ('微妙奇丽', 1), ('恢廓旷荡', 1),
        ('清净庄严', 2), ('超逾十方', 1), ('不寒不暑', 1),
        ('无有四时', 1), ('皆悉照见', 2),
    ],
    '音声说法': [
        ('演畅', 1), ('波扬', 1), ('法音宣流', 1), ('自然妙声', 1),
        ('八音', 2), ('妙音', 2), ('法音', 2), ('念佛念法念僧', 1),
    ],
}

# 目标词命中但段落含排除词时，该词不计分（成语/他义排除）
EXCLUDE_RULES = {
    '德风': ['君子'],
    '边地': ['边地下贱'],
}

CTX_WORDS = ['极乐', '安养', '安乐国', '安乐世界', '西方净土', '净土', '西方',
             '彼国', '彼土', '依报', '正报', '庄严', '阿弥陀', '弥陀', '宝刹']

# 预编译：词 → (主题, 级别)
WORD_INFO = {}
for cat, words in LEXICON.items():
    for w, lv in words:
        # 同一词若出现在多主题，保留首个主题
        if w not in WORD_INFO:
            WORD_INFO[w] = (cat, lv)

def score_para(p):
    """返回 (score, s1_hits, s2_distinct, ctx_distinct, cats, hits)"""
    s1, s2 = [], set()
    cats = Counter()
    for w, (cat, lv) in WORD_INFO.items():
        c = p.count(w)
        if not c:
            continue
        # 排除规则：成语/他义语境不计分
        if w in EXCLUDE_RULES and any(x in p for x in EXCLUDE_RULES[w]):
            continue
        if lv == 1:
            s1.append(w)
            cats[cat] += 3 * c
        else:
            s2.add(w)
            cats[cat] += 1 * c
    ctx = {w for w in CTX_WORDS if w in p}
    score = 3 * len(s1) + len(s2) + min(len(ctx), 2)
    return score, s1, s2, ctx, cats

def accept(p, score, s1, s2, ctx):
    if len(p) < 30:
        return False
    if s1:
        return True
    if len(s2) >= 3:
        return True
    if len(s2) >= 2 and ctx:
        return True
    return False

def split_paras(raw):
    return [ln.strip() for ln in raw.split('\n') if ln.strip()]

def main():
    lib = json.load(open(os.path.join(ROOT, 'public', 'library.json')))
    excerpts = []
    n_scanned = 0

    sources = []
    for s in lib['series']:
        for ch in s['chapters']:
            sources.append({
                'kind': '讲记', 'seriesId': s['id'], 'seriesTitle': s['title'],
                'chapterTitle': ch['title'], 'path': ch['path'],
            })
    for q in lib['qa']:
        sources.append({
            'kind': '问答', 'seriesId': 'qa', 'seriesTitle': '净土百问·问答',
            'chapterTitle': q['title'], 'path': q['path'],
        })

    for src in sources:
        fp = os.path.join(TEXT, src['path'])
        if not os.path.exists(fp):
            continue
        raw = open(fp, encoding='utf-8').read()
        paras = split_paras(raw)
        n_scanned += 1

        # 逐段评分
        marks = []
        for i, p in enumerate(paras):
            score, s1, s2, ctx, cats = score_para(p)
            ok = accept(p, score, s1, s2, ctx)
            marks.append((ok, score, s1, s2, cats))

        # 合并连续命中段
        i = 0
        while i < len(paras):
            if not marks[i][0]:
                i += 1
                continue
            j = i
            while j + 1 < len(paras) and marks[j + 1][0]:
                j += 1
            block = paras[i:j + 1]
            total_score = sum(m[1] for m in marks[i:j + 1])
            all_s1 = sorted({w for m in marks[i:j + 1] for w in m[2]})
            cat_counter = Counter()
            for m in marks[i:j + 1]:
                cat_counter.update(m[4])
            top_cats = [c for c, _ in cat_counter.most_common(4)]
            excerpts.append({
                'kind': src['kind'],
                'seriesId': src['seriesId'],
                'seriesTitle': src['seriesTitle'],
                'chapterTitle': src['chapterTitle'],
                'path': src['path'],
                'paraStart': i + 1,
                'paraEnd': j + 1,
                'score': total_score,
                's1': all_s1,
                'cats': top_cats,
                'text': '\n'.join(block),
            })
            i = j + 1

    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, 'daan_excerpts.json')
    json.dump({'total': len(excerpts), 'scanned': n_scanned, 'items': excerpts},
              open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

    # 统计
    chars = sum(len(e['text']) for e in excerpts)
    tier1 = [e for e in excerpts if e['s1']]
    print(f'扫描文件 {n_scanned} 个；提取摘录 {len(excerpts)} 条（含S1高精度词 {len(tier1)} 条），合计 {chars} 字')
    cat_all = Counter()
    for e in excerpts:
        cat_all.update(e['cats'][:1])
    print('主分类分布：')
    for c, n in cat_all.most_common():
        print(f'  {c}: {n}')

if __name__ == '__main__':
    main()
