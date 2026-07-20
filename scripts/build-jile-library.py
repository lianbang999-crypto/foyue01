#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「极乐世界资料库」：
  1. 经典原文/    ← CBETA 转换文本（繁体原样）+ 楞严势至章/华严行愿品节录
  2. 经典描述段/  ← 经典中描述极乐世界的段落（原文逐字引用，带段号出处）
  3. 大安法师讲集/ ← 核心摘录四卷（含S1高精度词）+ 总索引（全部1298条）
  4. data/        ← 结构化 JSON（供未来分镜/RAG）

前置：先跑 scripts/extract-jile-scenes.py 与 scripts/cbeta-p5-to-text.py
用法：python3 scripts/build-jile-library.py <cbeta_txt目录>
"""
import json, os, re, sys, shutil, importlib.util
from collections import Counter, OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIB = os.path.join(ROOT, '极乐世界资料库')

# 引入提取脚本的词典与判定逻辑
spec = importlib.util.spec_from_file_location(
    'extract', os.path.join(ROOT, 'scripts', 'extract-jile-scenes.py'))
ex = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ex)  # 模块名为 'extract'，不会触发其 __main__ 分支

from opencc import OpenCC
T2S = OpenCC('t2s')

# ---------- CBETA 书目 ----------
CBETA_META = OrderedDict([
    ('T12n0366', ('佛說阿彌陀經', '姚秦·鳩摩羅什譯', '三经')),
    ('T12n0365', ('佛說觀無量壽佛經', '劉宋·畺良耶舍譯', '三经')),
    ('T12n0360', ('佛說無量壽經', '曹魏·康僧鎧譯', '三经')),
    ('T12n0367', ('稱讚淨土佛攝受經', '唐·玄奘譯', '三经')),
    ('T12n0361', ('佛說無量清淨平等覺經', '後漢·支婁迦讖譯', '异译')),
    ('T12n0362', ('佛說阿彌陀三耶三佛薩樓佛檀過度人道經', '吳·支謙譯', '异译')),
    ('T12n0363', ('佛說大乘無量壽莊嚴經', '宋·法賢譯', '异译')),
    ('T12n0364', ('佛說大阿彌陀經', '宋·王日休校輯', '异译')),
    ('T26n1524', ('無量壽經優波提舍（往生論）', '天親菩薩造·元魏菩提流支譯', '祖师')),
    ('T40n1819', ('無量壽經優婆提舍願生偈註（往生論註）', '北魏·曇鸞註解', '祖师')),
    ('T47n1957', ('略論安樂淨土義', '北魏·曇鸞', '祖师')),
    ('T47n1958', ('安樂集', '唐·道綽', '祖师')),
    ('T37n1753', ('觀無量壽佛經疏（觀經四帖疏）', '唐·善導集記', '祖师')),
    ('T47n1961', ('淨土十疑論', '隋·智者大師說', '祖师')),
    ('T37n1751', ('觀無量壽佛經疏妙宗鈔', '宋·知禮述', '祖师')),
    ('T47n1970', ('龍舒增廣淨土文', '宋·王日休撰', '祖师')),
    ('T37n1762', ('阿彌陀經要解', '明·蕅益智旭解', '祖师')),
])

VOLUMES = OrderedDict([
    ('01-依报庄严·大地宝树莲池', ['宝地平整', '栏楯罗网行树', '宝树', '莲池德水', '莲华', '七宝众宝']),
    ('02-依报庄严·楼阁光明天乐', ['楼阁宫殿', '虚空天乐华雨', '德风光明', '化鸟演法', '音声说法', '衣食受用', '世界特性']),
    ('03-正报庄严·佛菩萨圣众', ['佛身相好', '圣众天人']),
    ('04-观想接引·十六观九品边地', ['十六观境', '临终接引', '边地疑城']),
])

def cut_chapter_files(src_dir, out_dir):
    """从楞严/华严全本切出净土五经所摄两章（原文原样）"""
    # 势至圆通章：单段，含「大勢至法王子」
    leng = open(os.path.join(src_dir, 'T19n0945.txt'), encoding='utf-8').read().split('\n')
    para = [l for l in leng if '大勢至法王子' in l]
    assert len(para) >= 1, '未找到势至圆通章段落'
    with open(os.path.join(out_dir, 'T19n0945-大勢至菩薩念佛圓通章（楞嚴經卷五節錄）.txt'), 'w', encoding='utf-8') as f:
        f.write('《大佛頂首楞嚴經》卷五·大勢至菩薩念佛圓通章（節錄）\n')
        f.write('[CBETA T19n0945 卷五，唐·般剌蜜帝譯；原文原樣節錄]\n\n')
        f.write(para[0] + '\n')
    # 华严卷四十（普贤行愿品）：自「大方廣佛華嚴經卷第四十」标题至文件此卷结束
    hy = open(os.path.join(src_dir, 'T10n0293.txt'), encoding='utf-8').read().split('\n')
    starts = [i for i, l in enumerate(hy) if l.startswith('### 大方廣佛華嚴經卷第四十')]
    s = starts[0]
    with open(os.path.join(out_dir, 'T10n0293-普賢行願品（四十華嚴卷四十）.txt'), 'w', encoding='utf-8') as f:
        f.write('《大方廣佛華嚴經》卷四十·入不思議解脫境界普賢行願品（末卷）\n')
        f.write('[CBETA T10n0293 卷四十，唐·般若譯；原文原樣節錄]\n\n')
        f.write('\n'.join(hy[s:]) + '\n')
    print('已切出：势至圆通章、普贤行愿品')

def extract_cbeta(src_dir):
    """对 CBETA 文本做描述段提取（繁→简匹配，引文保留繁体）"""
    results = OrderedDict()
    for wid, (title, byline, group) in CBETA_META.items():
        lines = open(os.path.join(src_dir, wid + '.txt'), encoding='utf-8').read().split('\n')
        paras = [l.strip() for l in lines[3:] if l.strip()]  # 跳过文件头3行
        marks = []
        cur_head = ''
        heads = []
        for p in paras:
            if p.startswith('### '):
                cur_head = p[4:].strip()
                marks.append(False)
                heads.append(cur_head)
                continue
            heads.append(cur_head)
            sp = T2S.convert(p)
            score, s1, s2, ctx, cats = ex.score_para(sp)
            ok = len(sp) >= 20 and (bool(s1) or len(s2) >= 3 or (len(s2) >= 2 and ctx))
            marks.append(ok)
        # 合并连续命中
        items = []
        i = 0
        while i < len(paras):
            if not marks[i]:
                i += 1
                continue
            j = i
            while j + 1 < len(paras) and marks[j + 1]:
                j += 1
            block = paras[i:j + 1]
            sp = T2S.convert('\n'.join(block))
            score, s1, s2, ctx, cats = ex.score_para(sp)
            items.append({
                'workId': wid, 'title': title, 'byline': byline, 'group': group,
                'head': heads[i], 'paraStart': i + 1, 'paraEnd': j + 1,
                'score': score, 's1': sorted(s1),
                'cats': [c for c, _ in cats.most_common(4)],
                'text': '\n'.join(block),
            })
            i = j + 1
        results[wid] = items
    return results

def write_cbeta_md(results, out_dir):
    groups = {
        '三经': ('01-净土五经描述段.md',
                 '# 净土五经描述段（经典原文）\n\n> 净土三经（阿弥陀经/观经/无量寿经）与称赞净土佛摄受经中描述西方极乐世界的段落。\n> **一切文字逐字取自 CBETA（cbeta-org/xml-p5），繁体原样，未作任何改写。**\n> 出处段号对应「经典原文」目录下同名文件的段落序（自正文起计）。\n> 五经之势至圆通章、普贤行愿品全文见「经典原文」目录节录文件。\n'),
        '异译': ('02-无量寿经异译描述段.md',
                 '# 无量寿经异译描述段（经典原文）\n\n> 《无量寿经》四种异译中描述极乐世界（依报/正报庄严）的段落，异译细节常可互补（如汉吴二译之泉池讲堂）。\n> **一切文字逐字取自 CBETA，繁体原样。**\n'),
        '祖师': ('03-祖师著述描述段.md',
                 '# 净土祖师著述描述段（原文）\n\n> 往生论（二十九种庄严）、往生论注、安乐集、观经四帖疏、净土十疑论、妙宗钞、龙舒净土文、弥陀要解中论述极乐世界庄严的段落。\n> **一切文字逐字取自 CBETA，繁体原样。**\n'),
    }
    for group, (fname, header) in groups.items():
        parts = [header]
        n_total = 0
        for wid, items in results.items():
            title, byline, g = CBETA_META[wid]
            if g != group:
                continue
            sel = items
            n_total += len(sel)
            parts.append(f'\n---\n\n## 《{title}》\n\n{byline} ｜ CBETA {wid} ｜ 描述段 {len(sel)} 条\n')
            for k, e in enumerate(sel, 1):
                head = f'（{e["head"]}）' if e['head'] else ''
                parts.append(f'\n### {wid}·{k} ｜ 段{e["paraStart"]}–{e["paraEnd"]}{head}\n')
                parts.append('\n'.join('> ' + ln for ln in e['text'].split('\n')))
                parts.append('')
        with open(os.path.join(out_dir, fname), 'w', encoding='utf-8') as f:
            f.write('\n'.join(parts))
        print(f'{fname}: {n_total} 条')

def write_daan_md(out_dir):
    d = json.load(open(os.path.join(LIB, 'data', 'daan_excerpts.json')))
    items = d['items']
    core = [e for e in items if e['s1']]
    ext = [e for e in items if not e['s1']]

    # 主分类归卷
    def volume_of(e):
        top = e['cats'][0] if e['cats'] else ''
        for vol, cats in VOLUMES.items():
            if top in cats:
                return vol
        return '02-依报庄严·楼阁光明天乐'

    by_vol = {v: [] for v in VOLUMES}
    for e in core:
        by_vol[volume_of(e)].append(e)

    for vol, cats in VOLUMES.items():
        arr = by_vol[vol]
        # 按主题→系列→讲次排序
        arr.sort(key=lambda e: (cats.index(e['cats'][0]) if e['cats'] and e['cats'][0] in cats else 99,
                                e['seriesId'], e['path'], e['paraStart']))
        parts = [f'# 大安法师讲集摘录 · {vol[3:]}\n',
                 f'> 本卷主题：{"、".join(cats)} ｜ 共 {len(arr)} 条（均含高精度场景词）',
                 '> 摘录逐字取自佛悦文库大安法师讲集文本，未改写；出处格式：系列·讲次（原文段落序）。',
                 '> 全部摘录（含扩展条目）的结构化数据见 data/daan_excerpts.json。\n']
        cur_cat = None
        n = 0
        for e in arr:
            top = e['cats'][0] if e['cats'] else '其他'
            if top != cur_cat:
                cur_cat = top
                parts.append(f'\n---\n\n## {cur_cat}\n')
            n += 1
            kws = '、'.join(e['s1'][:6])
            parts.append(f'\n### {n}. {e["seriesTitle"]} · {e["chapterTitle"]}（段{e["paraStart"]}–{e["paraEnd"]}）')
            parts.append(f'关键词：{kws}\n')
            parts.append(e['text'])
            parts.append('')
        with open(os.path.join(out_dir, f'{vol}.md'), 'w', encoding='utf-8') as f:
            f.write('\n'.join(parts))
        print(f'{vol}.md: {len(arr)} 条')

    # 总索引：核心统计 + 扩展条目索引
    parts = ['# 大安法师讲集 · 极乐世界摘录总索引\n',
             f'> 扫描 38 部讲记 241 篇 + 问答 820 条；共提取摘录 {len(items)} 条（约 {sum(len(e["text"]) for e in items)//10000} 万字）。',
             f'> 其中含高精度场景词的核心摘录 {len(core)} 条已全文收入本目录四卷；',
             f'> 其余扩展摘录 {len(ext)} 条在下方索引（全文见 data/daan_excerpts.json）。\n',
             '## 主题分布（按首要主题）\n']
    cnt = Counter(e['cats'][0] for e in items if e['cats'])
    for c, k in cnt.most_common():
        parts.append(f'- {c}：{k} 条')
    parts.append('\n## 各系列命中条数\n')
    scnt = Counter(e['seriesTitle'] for e in items)
    for s, k in scnt.most_common():
        parts.append(f'- {s}：{k} 条')
    parts.append('\n---\n\n## 扩展摘录索引（不含高精度词，供进一步筛用）\n')
    for e in ext:
        first = e['text'][:42].replace('\n', ' ')
        parts.append(f'- {e["seriesTitle"]}·{e["chapterTitle"]} 段{e["paraStart"]}–{e["paraEnd"]}｜{e["cats"][0] if e["cats"] else ""}｜{first}…')
    with open(os.path.join(out_dir, '00-总索引.md'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(parts))
    print(f'00-总索引.md: 核心{len(core)} + 扩展{len(ext)}')

def main():
    src_dir = sys.argv[1]
    orig_dir = os.path.join(LIB, '经典原文')
    desc_dir = os.path.join(LIB, '经典描述段')
    daan_dir = os.path.join(LIB, '大安法师讲集')
    data_dir = os.path.join(LIB, 'data')
    for p in (orig_dir, desc_dir, daan_dir, data_dir):
        os.makedirs(p, exist_ok=True)

    # 1. 经典原文（重命名带经题）
    for wid, (title, byline, group) in CBETA_META.items():
        short = re.sub(r'（.*?）', '', title)
        shutil.copy(os.path.join(src_dir, wid + '.txt'),
                    os.path.join(orig_dir, f'{wid}-{short}.txt'))
    cut_chapter_files(src_dir, orig_dir)
    print(f'经典原文：{len(CBETA_META)} 部全本 + 2 节录')

    # 2. 经典描述段
    results = extract_cbeta(src_dir)
    json.dump({wid: items for wid, items in results.items()},
              open(os.path.join(data_dir, 'cbeta_excerpts.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    write_cbeta_md(results, desc_dir)

    # 3. 大安法师讲集四卷 + 总索引
    write_daan_md(daan_dir)

if __name__ == '__main__':
    main()
