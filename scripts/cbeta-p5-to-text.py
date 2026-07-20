#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CBETA XML P5 → 纯文本转换器（用于「极乐世界资料库」经典原文卷）。

原则（铁律：经文逐字取自 CBETA，不增删改写）：
  - 正文取底本：校勘 <choice>/<app> 取 <lem>，舍 <rdg>
  - 脚注 <note> 一律不混入正文
  - 缺字 <g>：优先取 charDecl 的 unicode 映射，其次 normalized 映射，无则以 □ 占位
  - 保留卷/品结构（cb:juan、head 输出为标题行）
  - 偈颂 <lg>/<l> 按行输出

用法：python3 scripts/cbeta-p5-to-text.py <xml文件...> -o <输出目录>
"""
import sys, os, re
import xml.etree.ElementTree as ET

TEI = '{http://www.tei-c.org/ns/1.0}'
CB = '{http://www.cbeta.org/ns/1.0}'

def tag_name(el):
    return el.tag.replace(TEI, '').replace(CB, 'cb:')

def build_gaiji_map(root):
    """charDecl 缺字映射：id → 字符"""
    m = {}
    for char in root.iter(TEI + 'char'):
        cid = char.get('{http://www.w3.org/XML/1998/namespace}id')
        if not cid:
            continue
        uni, norm = None, None
        for mp in char.findall(TEI + 'mapping'):
            t = mp.get('type') or mp.get('cb:type') or ''
            txt = (mp.text or '').strip()
            if not txt:
                continue
            if 'unicode' in t:
                uni = txt
            elif 'normal' in t:
                norm = txt
        # mapping 内容可能是 U+XXXX 形式
        for v in (uni, norm):
            if v and re.fullmatch(r'U\+[0-9A-Fa-f]+', v):
                v2 = chr(int(v[2:], 16))
                m[cid] = v2
                break
            elif v:
                m[cid] = v
                break
        if cid not in m:
            m[cid] = '□'
    return m

class Walker:
    def __init__(self, gaiji):
        self.gaiji = gaiji
        self.out = []       # 段落列表
        self.buf = []       # 当前段缓冲

    def flush(self):
        t = ''.join(self.buf).strip()
        t = re.sub(r'\s+', '', t)  # 经文内空白（换行缩进）全部去除
        if t:
            self.out.append(t)
        self.buf = []

    def heading(self, text):
        self.flush()
        t = re.sub(r'\s+', ' ', text).strip()
        if t:
            self.out.append('### ' + t)

    def walk(self, el):
        tn = tag_name(el)
        if tn == 'note':            # 脚注不入正文
            return
        if tn == 'rdg':             # 校勘异读舍去
            return
        if tn in ('teiHeader', 'charDecl', 'back'):
            return
        if tn == 'g':               # 缺字
            ref = (el.get('ref') or '').lstrip('#')
            self.buf.append(self.gaiji.get(ref, '□'))
            if el.tail:
                self.buf.append(el.tail)
            return
        if tn == 'lb' or tn == 'pb' or tn == 'milestone' or tn == 'anchor':
            if el.tail:
                self.buf.append(el.tail)
            return
        if tn in ('head', 'cb:mulu', 'cb:jhead'):
            # cb:mulu 是目录点，head 是标题
            txt = ''.join(el.itertext())
            if tn != 'cb:mulu':
                self.heading(txt)
            if el.tail:
                self.buf.append(el.tail)
            return
        if tn in ('p', 'byline', 'cb:docNumber'):
            self.flush()
            for child in self._iter_children(el):
                pass
            if el.tail:
                self.flush()
                self.buf.append(el.tail)
            else:
                self.flush()
            return
        if tn == 'lg':              # 偈颂
            self.flush()
            for l in el:
                if tag_name(l) == 'l':
                    lw = Walker(self.gaiji)
                    lw._inline(l)
                    line = re.sub(r'\s+', '', ''.join(lw.buf))
                    if line:
                        self.out.append('　' + line)
                else:
                    self.walk(l)
            if el.tail:
                self.buf.append(el.tail)
            return
        # 默认：递归
        if el.text:
            self.buf.append(el.text)
        for child in el:
            self.walk(child)
        if el.tail:
            self.buf.append(el.tail)

    def _iter_children(self, el):
        if el.text:
            self.buf.append(el.text)
        for child in el:
            self.walk(child)
        yield None

    def _inline(self, el):
        tn = tag_name(el)
        if tn in ('note', 'rdg'):
            return
        if tn == 'g':
            ref = (el.get('ref') or '').lstrip('#')
            self.buf.append(self.gaiji.get(ref, '□'))
            if el.tail:
                self.buf.append(el.tail)
            return
        if el.text:
            self.buf.append(el.text)
        for c in el:
            self._inline(c)
        if el.tail:
            self.buf.append(el.tail)

def convert(path, outdir):
    tree = ET.parse(path)
    root = tree.getroot()
    # 标题（核实用）
    title = ''
    for t in root.iter(TEI + 'title'):
        if t.get('level') == 'm' and (t.text or '').strip():
            title = t.text.strip()
            break
    gaiji = build_gaiji_map(root)
    body = root.find(f'{TEI}text/{TEI}body')
    w = Walker(gaiji)
    w.walk(body)
    w.flush()
    base = os.path.splitext(os.path.basename(path))[0]
    out = os.path.join(outdir, base + '.txt')
    with open(out, 'w', encoding='utf-8') as f:
        f.write(f'《{title}》\n[CBETA {base}，取自 cbeta-org/xml-p5；校勘取底本，脚注未混入，缺字以□或通用字映射]\n\n')
        f.write('\n'.join(w.out))
    n = sum(len(x) for x in w.out)
    print(f'{base}  {title}  段落{len(w.out)}  {n}字')
    return title

if __name__ == '__main__':
    args = sys.argv[1:]
    outdir = '.'
    if '-o' in args:
        i = args.index('-o')
        outdir = args[i + 1]
        args = args[:i] + args[i + 2:]
    os.makedirs(outdir, exist_ok=True)
    for p in args:
        convert(p, outdir)
