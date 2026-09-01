#!/usr/bin/env python3
# 问道检索索引管线：文库正文（public/text/）→ 切块 → 两份索引
#
#   一、向量索引  chunk → bge-m3 → scripts/vectors.ndjson
#       灌入：npx wrangler vectorize upsert foyue-wenku --file=scripts/vectors.ndjson --batch-size 500
#       ※ 用 upsert 不用 insert：既补得上新增的块，又不会撞已有 id。
#
#   二、全文索引  chunk → scripts/kb-rows.ndjson → scripts/push-kb.py 推给 Worker
#       为什么要这一份：纯向量对「戒杀」「十念记数」这类短名相常常召不回 ——
#       它们在长句里语义占比太小。关键词召回补上这一路，两路再做 RRF 融合。
#
#       这里只出正文，**不出 bigram**：切二元由 Worker 那边现算（/api/admin/kbindex）。
#       两个缘故 —— 一是与查询用的是同一个 cjkBigrams，两边永远不会切法不一致
#       （那种不一致不报错、只是「搜不到」，最难查）；二是 bigram 比正文还大一倍多，
#       不上传省一大半流量。
#       原先想过在本地生成 SQL 用 wrangler d1 execute 灌，实测不行：
#       每次调用光启动就 40 秒，390KB 的文件即开始超时，全量要跑五个多小时。
#
# **两份的块 id 必须完全一致** —— 融合就是靠 id 对齐的。故两份由同一次切块产出，
# 切块逻辑（CHUNK/OVERLAP/split_chunks）改动后两份都要重灌，不能只灌一份。
#
# 运行：
#   python3 scripts/build-index.py --dry        # 只切块看数目与样例 id，不写任何文件
#   python3 scripts/build-index.py --fts-only   # 只重出全文索引行（不调用 API，秒出）
#   SF_KEY=sk-xxx python3 scripts/build-index.py   # 两份都出（要向量化，几分钟）

import json, os, re, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIB = json.loads((ROOT / 'public/library.json').read_text('utf-8'))
OUT = ROOT / 'scripts/vectors.ndjson'
KB_OUT = ROOT / 'scripts/kb-rows.ndjson'

CHUNK = 700     # 目标块长（字符）
OVERLAP = 80    # 相邻块重叠
BATCH = 32      # 每次 embedding 请求的输入条数

DRY = '--dry' in sys.argv
FTS_ONLY = '--fts-only' in sys.argv


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



def embed(texts: list[str], key: str, retries=5) -> list[list[float]]:
    body = json.dumps({'model': 'BAAI/bge-m3', 'input': texts}).encode()
    for i in range(retries):
        try:
            req = urllib.request.Request(
                'https://api.siliconflow.cn/v1/embeddings', data=body,
                headers={'Authorization': f'Bearer {key}', 'Content-Type': 'application/json'})
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
n_jiang = len(items)
for q in LIB['qa']:
    text = (ROOT / 'public/text' / q['path']).read_text('utf-8')
    for j, chunk in enumerate(split_chunks(text)):
        items.append((
            f"qa-{q['n']:03d}-{j:02d}", chunk,
            {'t': chunk, 'title': q['title'], 'series': '学佛问答', 'path': q['path'], 'kind': '问答'},
        ))

dupes = len(items) - len({x[0] for x in items})
print(f'共 {len(items)} 块（讲记 {n_jiang} + 问答 {len(items) - n_jiang}）')
print(f'样例 id：{items[0][0]} | {items[n_jiang][0]} | {items[-1][0]}')
if dupes:
    sys.exit(f'！有 {dupes} 个重复 id —— 融合靠 id 对齐，重复会串段，必须先修 library.json')

if DRY:
    print('\n--dry：未写任何文件。')
    sys.exit(0)


# ---- 二、全文索引的行（bigram 由 Worker 现算，这里只出正文）----
def write_fts():
    with open(KB_OUT, 'w', encoding='utf-8') as f:
        for vid, txt, meta in items:
            f.write(json.dumps({
                'cid': vid, 'text': txt, 'title': meta['title'],
                'series': meta['series'], 'path': meta['path'], 'kind': meta['kind'],
            }, ensure_ascii=False) + '\n')
    print(f'\n✓ 全文索引行：{len(items)} 行 → {KB_OUT.name}（{KB_OUT.stat().st_size/1e6:.1f} MB）')
    print('  灌库：ADMIN_TOKEN=xxx python3 scripts/push-kb.py --reset')


write_fts()
if FTS_ONLY:
    print('\n--fts-only：跳过向量化。')
    sys.exit(0)


# ---- 一、向量索引 ----
SF_KEY = os.environ.get('SF_KEY') or sys.exit('缺少 SF_KEY 环境变量（只出全文索引可加 --fts-only）')
print(f'\n开始向量化（批 {BATCH}）', flush=True)
t0 = time.time()
with open(OUT, 'w', encoding='utf-8') as f:
    for i in range(0, len(items), BATCH):
        batch = items[i:i + BATCH]
        vecs = embed([x[1] for x in batch], SF_KEY)
        for (vid, _, meta), v in zip(batch, vecs):
            f.write(json.dumps({'id': vid, 'values': v, 'metadata': meta}, ensure_ascii=False) + '\n')
        done = i + len(batch)
        if done % (BATCH * 10) < BATCH:
            rate = done / (time.time() - t0)
            print(f'  {done}/{len(items)}  ({rate:.0f} 块/秒)', flush=True)

print(f'✓ 向量索引：{len(items)} 块 → {OUT.name}（{OUT.stat().st_size/1e6:.0f} MB），'
      f'耗时 {(time.time()-t0)/60:.1f} 分钟')
print('  灌库：npx wrangler vectorize upsert foyue-wenku '
      '--file=scripts/vectors.ndjson --batch-size 500')
