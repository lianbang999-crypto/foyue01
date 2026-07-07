#!/usr/bin/env python3
# 问道向量索引管线：文库正文（public/text/）→ 切块 → bge-m3 向量化 → NDJSON
# 之后用 `npx wrangler vectorize insert foyue-wenku --file=scripts/vectors.ndjson --batch-size 500` 灌入
#
# 运行：SF_KEY=sk-xxx python3 scripts/build-index.py
# 说明：chunk 正文存入向量 metadata（Vectorize 单条 metadata 上限 10KiB，chunk ≤900字 足够安全）

import json, os, re, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIB = json.loads((ROOT / 'public/library.json').read_text('utf-8'))
OUT = ROOT / 'scripts/vectors.ndjson'
SF_KEY = os.environ.get('SF_KEY') or sys.exit('缺少 SF_KEY 环境变量')

CHUNK = 700     # 目标块长（字符）
OVERLAP = 80    # 相邻块重叠
BATCH = 32      # 每次 embedding 请求的输入条数


def split_chunks(text: str) -> list[str]:
    """按句边界切块：目标 700 字，重叠 80 字"""
    text = re.sub(r'\n+', '\n', text).strip()
    if len(text) <= CHUNK:
        return [text]
    # 先按句切
    sents = re.split(r'(?<=[。！？；])', text)
    chunks, cur = [], ''
    for s in sents:
        if len(cur) + len(s) > CHUNK and cur:
            chunks.append(cur)
            cur = cur[-OVERLAP:] + s  # 带上上一块尾部作重叠
        else:
            cur += s
    if cur.strip():
        chunks.append(cur)
    return [c.strip() for c in chunks if len(c.strip()) > 40]


def embed(texts: list[str], retries=5) -> list[list[float]]:
    body = json.dumps({'model': 'BAAI/bge-m3', 'input': texts}).encode()
    for i in range(retries):
        try:
            req = urllib.request.Request(
                'https://api.siliconflow.cn/v1/embeddings', data=body,
                headers={'Authorization': f'Bearer {SF_KEY}', 'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=120) as r:
                d = json.load(r)
            return [x['embedding'] for x in sorted(d['data'], key=lambda x: x['index'])]
        except Exception as e:
            wait = 3 * (i + 1)
            print(f'  embedding 重试 {i+1}: {str(e)[:80]}，等 {wait}s', flush=True)
            time.sleep(wait)
    raise RuntimeError('embedding 连续失败')


# ---- 收集全部块 ----
items = []  # (id, text, metadata)
for s in LIB['series']:
    for c in s['chapters']:
        text = (ROOT / 'public/text' / c['path']).read_text('utf-8')
        for j, chunk in enumerate(split_chunks(text)):
            items.append((
                f"{s['id']}-{c['n']:02d}-{j:03d}", chunk,
                {'t': chunk, 'title': c['title'], 'series': s['title'], 'path': c['path'], 'kind': '讲记'},
            ))
for q in LIB['qa']:
    text = (ROOT / 'public/text' / q['path']).read_text('utf-8')
    for j, chunk in enumerate(split_chunks(text)):
        items.append((
            f"qa-{q['n']:03d}-{j:02d}", chunk,
            {'t': chunk, 'title': q['title'], 'series': '学佛问答', 'path': q['path'], 'kind': '问答'},
        ))

print(f'共 {len(items)} 块，开始向量化（批 {BATCH}）', flush=True)

t0 = time.time()
with open(OUT, 'w', encoding='utf-8') as f:
    for i in range(0, len(items), BATCH):
        batch = items[i:i + BATCH]
        vecs = embed([x[1] for x in batch])
        for (vid, _, meta), v in zip(batch, vecs):
            f.write(json.dumps({'id': vid, 'values': v, 'metadata': meta}, ensure_ascii=False) + '\n')
        done = i + len(batch)
        if done % (BATCH * 10) < BATCH:
            rate = done / (time.time() - t0)
            print(f'  {done}/{len(items)}  ({rate:.0f} 块/秒)', flush=True)

print(f'✓ 向量化完成：{len(items)} 块 → {OUT.name}（{OUT.stat().st_size/1e6:.0f} MB），'
      f'耗时 {(time.time()-t0)/60:.1f} 分钟', flush=True)
