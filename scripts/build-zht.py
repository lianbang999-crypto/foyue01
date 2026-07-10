#!/usr/bin/env python3
# 生成简↔繁字级对照表 → public/js/zh-t.js（构建产物，惰性加载的 ES 模块）
# 依赖：pip3 install opencc（OpenCC 字表，s2t/t2s 各取单字映射）
import json
from opencc import OpenCC

s2t = OpenCC('s2t')
t2s = OpenCC('t2s')

def build(conv):
    src, dst = [], []
    # CJK 统一表意区（常用字全在此区；扩展区罕用字省略以控制体积）
    for cp in range(0x4E00, 0x9FFF + 1):
        ch = chr(cp)
        out = conv.convert(ch)
        # 只收基本平面单字映射：超平面罕用字占两个 UTF-16 单元，会让 JS 按下标取字时整表错位
        if out != ch and len(out) == 1 and ord(out) <= 0xFFFF:
            src.append(ch)
            dst.append(out)
    return ''.join(src), ''.join(dst)

sf, st = build(s2t)
tf, ts = build(t2s)

with open('public/js/zh-t.js', 'w', encoding='utf-8') as f:
    f.write('// 简↔繁 字级对照表（OpenCC 字表生成，勿手改；重建：python3 scripts/build-zht.py）\n')
    f.write(f'export const S2T_FROM = {json.dumps(sf, ensure_ascii=False)};\n')
    f.write(f'export const S2T_TO = {json.dumps(st, ensure_ascii=False)};\n')
    f.write(f'export const T2S_FROM = {json.dumps(tf, ensure_ascii=False)};\n')
    f.write(f'export const T2S_TO = {json.dumps(ts, ensure_ascii=False)};\n')

print(f's2t {len(sf)} 对，t2s {len(tf)} 对')
