#!/usr/bin/env python3
"""把全文索引的行推给线上 Worker，由它现算 bigram 写进 D1。

为什么不用 wrangler d1 execute 灌 SQL：实测每次调用光启动就 40 秒，
文件到 390KB 即开始超时，九千行要跑五个多小时且一路在断。经这个接口走一条连接
分批推，几分钟就完。bigram 也因此在服务端算 —— 与查询用的是同一个 cjkBigrams，
两边永远不会切法不一致（那种不一致不报错、只是「搜不到」，最难查）。

用法：
    ADMIN_TOKEN=xxx python3 scripts/push-kb.py --reset      # 整表重建后全量灌
    ADMIN_TOKEN=xxx python3 scripts/push-kb.py              # 接着灌（不重建表）
    ADMIN_TOKEN=xxx python3 scripts/push-kb.py --from 3000  # 断在半路时从第 N 行接着来

--reset 会 DROP 掉整张 chunks_fts 重建。它只影响检索索引，不碰留言与念佛计数
（那些在另一个库 bojingtai-cmt）。但重建期间问道的关键词召回会短暂落空 ——
不会报错，只是那几分钟里退回纯向量。
"""
import json, os, sys, time, urllib.error, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'scripts/kb-rows.ndjson'
BASE = os.environ.get('FOYUE_BASE', 'https://foyue.org')
TOKEN = os.environ.get('ADMIN_TOKEN') or sys.exit('缺少 ADMIN_TOKEN 环境变量')

BATCH = 150      # 每批行数。D1 batch 一次几百条没问题，压低些是为了单批失败时重试便宜
TIMEOUT = 120


# 必须带个正常 UA：Python-urllib 的默认 UA 会被 Cloudflare 挡成 403（error code 1010，
# 浏览器指纹拦截）。它挡在 Worker 之前，口令对不对都轮不到我们的代码说话 ——
# 别在这上头查半天。
UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 '\
     '(KHTML, like Gecko) Chrome/126.0 Safari/537.36 foyue-kb-push'


def post(path, payload):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
        headers={'Authorization': f'Bearer {TOKEN}', 'Content-Type': 'application/json',
                 'User-Agent': UA},
        method='POST')
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return json.load(r)


def post_retry(path, payload, tries=4):
    for i in range(tries):
        try:
            return post(path, payload)
        except urllib.error.HTTPError as e:
            body = e.read().decode('utf-8', 'replace')[:200]
            if e.code in (401, 403):
                sys.exit(f'！口令不对（{e.code}）：{body}')
            if e.code == 400:
                sys.exit(f'！请求有误，不重试：{body}')
            last = f'HTTP {e.code} {body}'
        except Exception as e:
            last = str(e)[:200]
        if i < tries - 1:
            wait = 4 * (i + 1)
            print(f'    重试 {i+1}：{last}，等 {wait}s', flush=True)
            time.sleep(wait)
    raise RuntimeError(last)


if not SRC.is_file():
    sys.exit(f'！找不到 {SRC.name}，先跑 python3 scripts/build-index.py --fts-only')

rows = [json.loads(l) for l in SRC.open(encoding='utf-8') if l.strip()]
start = 0
if '--from' in sys.argv:
    start = int(sys.argv[sys.argv.index('--from') + 1])
print(f'共 {len(rows)} 行，从第 {start} 行开始，每批 {BATCH} → {BASE}')

if '--reset' in sys.argv:
    if start:
        sys.exit('！--reset 与 --from 不能同用：重建了表再从半路灌，前面那截就没了')
    print('整表重建 …', flush=True)
    post_retry('/api/admin/kbindex?reset=1', {})

t0 = time.time()
done = start
for i in range(start, len(rows), BATCH):
    part = rows[i:i + BATCH]
    post_retry('/api/admin/kbindex', {'rows': part})
    done = i + len(part)
    el = time.time() - t0
    rate = (done - start) / el if el else 0
    eta = (len(rows) - done) / rate / 60 if rate else 0
    print(f'  {done}/{len(rows)}  ({rate:.0f} 行/秒，约剩 {eta:.1f} 分钟)', flush=True)

print(f'\n✓ 灌完 {done} 行，耗时 {(time.time()-t0)/60:.1f} 分钟')
print('  自检：curl -s https://foyue.org/api/ask/health')
