#!/usr/bin/env python3
"""问道检索的评测：把「答得准不准、可不可信」变成几个能对比的数。

改了 worker/index.js 里任何一处检索参数或提示词之后跑一遍，与上一次的数对比升降，
别凭手气试几个问题就说「感觉好些了」—— 那种感觉多半只是换了个说法而已。

量四件事：
  · 召回率   —— 该出现的篇目有没有被检索到（题集里 expect 标了才算得出）
  · 引用率   —— 回答里有没有标 [n]；一句出处都不标的回答是没法核对的
  · 拒答率   —— 离题问题该拒的拒了没有（scope=out 的题应当 100% 拒答）
  · 忠实率   —— 读服务端的引用自检：编号有没有越界、直引能不能逐字对上
另附 top1 相关度分的分布，调 RERANK_MIN 时看它（--scores 只看这一项，跑得快）。

用法：
    python3 scripts/eval-ask.py                       # 全量评测
    python3 scripts/eval-ask.py --scores              # 只量相关度分，不看回答
    python3 scripts/eval-ask.py --out scripts/eval-result.json
    python3 scripts/eval-ask.py --base http://127.0.0.1:8787

线上限流是每 IP 每分钟 8 问，脚本自己让着点（PACE）；题多时会跑几分钟。
"""
import json, sys, time, urllib.error, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
QS = ROOT / 'scripts/eval-questions.json'

def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default

BASE = arg('--base', 'https://foyue.org').rstrip('/')
OUT = arg('--out')
SCORES_ONLY = '--scores' in sys.argv
PACE = 8.0        # 秒/题：线上每分钟 8 问，留一点余量
TIMEOUT = 120
UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 '\
     '(KHTML, like Gecko) Chrome/126.0 Safari/537.36 foyue-eval'

# 护栏定句的开头，用来判「拒答了没有」。改 worker 里那两句时要跟着改。
REFUSAL = ('文库中未找到', '抱歉，文库检索暂时不可用')


def ask(q):
    """发一问，把 SSE 拆成 {sources, answer, verify, topScore}。"""
    # 必须带个正常 UA：Python-urllib 的默认 UA 会被 Cloudflare 当爬虫挡成 403，
    # 而它挡在 Worker 之前，代码里怎么改都没用 —— 别在这上头查半天。
    req = urllib.request.Request(
        BASE + '/api/ask', data=json.dumps({'q': q}, ensure_ascii=False).encode('utf-8'),
        headers={'Content-Type': 'application/json', 'User-Agent': UA}, method='POST')
    sources, answer, done = [], '', {}
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        for raw in r:
            line = raw.decode('utf-8', 'replace').rstrip('\n')
            if not line.startswith('data: '):
                continue
            try:
                d = json.loads(line[6:])
            except Exception:
                continue
            if isinstance(d, list):
                sources = d
            elif isinstance(d, dict) and 'text' in d:
                answer += d['text']
            elif isinstance(d, dict):
                done = d
    return {
        'sources': sources, 'answer': answer,
        'verify': done.get('verify'), 'topScore': done.get('topScore'),
    }


def hit(sources, expect):
    """出处里有没有命中 expect 里的任一关键字（比系列名与篇名）。"""
    blob = ' '.join((s.get('series', '') + s.get('title', '') + s.get('path', '')) for s in sources)
    return any(k in blob for k in expect)


def main():
    items = json.loads(QS.read_text('utf-8'))
    rows = []
    print(f'{len(items)} 题 → {BASE}（每题间隔 {PACE}s，约 {len(items)*PACE/60:.1f} 分钟）\n')
    for i, it in enumerate(items, 1):
        q, scope, expect = it['q'], it.get('scope', 'in'), it.get('expect', [])
        try:
            r = ask(q)
        except urllib.error.HTTPError as e:
            print(f'  {i:>2}. [HTTP {e.code}] {q}')
            rows.append({**it, 'error': f'HTTP {e.code}'})
            time.sleep(PACE)
            continue
        except Exception as e:
            print(f'  {i:>2}. [失败] {q} — {str(e)[:60]}')
            rows.append({**it, 'error': str(e)[:120]})
            time.sleep(PACE)
            continue

        refused = r['answer'].lstrip().startswith(REFUSAL)
        v = r['verify'] or {}
        row = {
            **it,
            'n_sources': len(r['sources']),
            'topScore': r['topScore'],
            'refused': refused,
            'cited': v.get('cited', 0),
            'invalid': v.get('invalid', 0),
            'quoteChecked': v.get('quoteChecked', 0),
            'quoteOk': v.get('quoteOk', 0),
            'faithful': v.get('faithful'),
            'recall': (hit(r['sources'], expect) if expect else None),
            'answer': r['answer'][:400],
        }
        rows.append(row)
        ts = '缓存' if r['topScore'] is None else f"{r['topScore']:.3f}"
        if SCORES_ONLY:
            print(f'  {i:>2}. {ts:>6}  [{scope}] {q}')
        else:
            mark = '拒答' if refused else f"{row['cited']}处引用"
            rc = '' if row['recall'] is None else ('  召回✓' if row['recall'] else '  召回✗')
            print(f'  {i:>2}. {ts:>6}  {mark:<8}{rc}  [{scope}] {q}')
        if i < len(items):
            time.sleep(PACE)

    ok = [r for r in rows if 'error' not in r]
    ins = [r for r in ok if r.get('scope', 'in') == 'in']
    outs = [r for r in ok if r.get('scope') == 'out']
    rec = [r for r in ins if r.get('recall') is not None]

    def pct(n, d):
        return f'{100*n/d:.0f}%（{n}/{d}）' if d else '—'

    print('\n══════ 汇总 ══════')
    print(f'  在题 {len(ins)} 题 · 离题 {len(outs)} 题 · 失败 {len(rows)-len(ok)} 题')
    if rec:
        print(f'  召回率      {pct(sum(1 for r in rec if r["recall"]), len(rec))}   （只统计标了 expect 的题）')
    if ins:
        print(f'  在题引用率  {pct(sum(1 for r in ins if r["cited"] > 0), len(ins))}')
        print(f'  在题误拒率  {pct(sum(1 for r in ins if r["refused"]), len(ins))}   ← 该答却拒了，越低越好')
        faith = [r for r in ins if r['faithful'] is not None and not r['refused']]
        if faith:
            print(f'  忠实率      {pct(sum(1 for r in faith if r["faithful"]), len(faith))}   （编号无越界且直引逐字对得上）')
        qc = sum(r['quoteChecked'] for r in ins)
        print(f'  直引命中率  {pct(sum(r["quoteOk"] for r in ins), qc)}')
        print(f'  越界编号    {sum(r["invalid"] for r in ins)} 处   ← 必须是 0')
    if outs:
        print(f'  离题拒答率  {pct(sum(1 for r in outs if r["refused"]), len(outs))}   ← 应为 100%')
    scored = [r['topScore'] for r in ok if r['topScore'] is not None]
    if scored:
        si = [r['topScore'] for r in ins if r['topScore'] is not None]
        so = [r['topScore'] for r in outs if r['topScore'] is not None]
        if si: print(f'  在题分      {min(si):.3f} ~ {max(si):.3f}')
        if so: print(f'  离题分      {min(so):.3f} ~ {max(so):.3f}')
        if si and so:
            print(f'  分水岭      离题最高 {max(so):.3f} ↔ 在题最低 {min(si):.3f}'
                  + ('   ✓ 分得开' if max(so) < min(si) else '   ！重叠了，RERANK_MIN 定不出安全值'))

    if OUT:
        Path(OUT).write_text(json.dumps(rows, ensure_ascii=False, indent=2), 'utf-8')
        print(f'\n明细已写入 {OUT}')


if __name__ == '__main__':
    main()
