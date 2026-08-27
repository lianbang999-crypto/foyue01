#!/usr/bin/env python3
"""
§126 D2：小鹿动作协议评测集（AI-UX 准确性防线 7 —— 评测集门禁）。

把「模型质量」从感觉变成数字：换模型、改提示词，先跑这个，达标（≥90%）才上线。

设计要点：
- 提示词**直接从 AiActions.kt 抽取**（PROTOCOL / PERSONA），不抄一份 —— 抄本必漂移；
  nowLine/dateAnchors 在这里按同一格式现算。
- 直连 OpenRouter（Key 读 secrets.txt，不进 git）：测的是「模型 × 提示词」的质量；
  worker 管线另有链路测法，不混在一起。
- 解析口径与客户端一致：fenced ```json 块 → actions 数组 → 类型白名单。

用法：
  python3 scripts/ai_eval.py                 # 用 worker.js 里的主力模型
  python3 scripts/ai_eval.py --model qwen/qwen3.6-plus
  python3 scripts/ai_eval.py --only T1,U15   # 只跑指定用例
退出码：0 = 达标；1 = 不达标或跑不了。
"""
import argparse
import datetime as dt
import json
import pathlib
import re
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
WEEK = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]


def read_key() -> str:
    for line in (ROOT / "secrets.txt").read_text().splitlines():
        m = re.match(r"^OPENROUTER_KEY\s*=\s*(\S+)", line)
        if m:
            return m.group(1)
    sys.exit("✗ secrets.txt 里找不到 OPENROUTER_KEY")


def default_model() -> str:
    wk = (ROOT / "server/src/worker.js").read_text()
    m = re.search(r"env\.OR_CHAT_MODEL \|\| '([^']+)'", wk)
    return m.group(1) if m else "openai/gpt-5.6-luna"


def extract_kt(name: str) -> str:
    """从 AiActions.kt 抽 raw string 常量（PROTOCOL / PERSONA）—— 单一真源，不抄本"""
    src = (ROOT / "app/src/main/java/com/looka/app/ai/AiActions.kt").read_text()
    m = re.search(r'private val %s = """(.*?)"""' % name, src, re.S)
    if not m:
        sys.exit(f"✗ AiActions.kt 里找不到 {name}")
    return m.group(1).strip()


def now_line() -> str:
    n = dt.datetime.now()
    return f"{n.date()} {WEEK[n.weekday()]} {n.hour:02d}:{n.minute:02d}"


def date_anchors() -> str:
    t = dt.date.today()
    mon = t + dt.timedelta(days=8 - t.isoweekday())
    return (f"今天={t}，明天={t + dt.timedelta(days=1)}，后天={t + dt.timedelta(days=2)}，"
            f"本周末={t + dt.timedelta(days=6 - t.isoweekday())}，下周一={mon}")


def subst(s: str) -> str:
    """占位符：{iso:+N} → YYYY-MM-DD；{cn:+N} → M月D日"""
    t = dt.date.today()

    def iso(m): return str(t + dt.timedelta(days=int(m.group(1))))
    def cn(m):
        d = t + dt.timedelta(days=int(m.group(1)))
        return f"{d.month}月{d.day}日"
    return re.sub(r"\{cn:\+?(-?\d+)\}", cn, re.sub(r"\{iso:\+?(-?\d+)\}", iso, s))


def build_system(case: dict, persona: str, protocol: str) -> str:
    agenda = "用户日程（近7天与未来14天，[e数字] 是它的 id）：\n"
    agenda += (subst(case["context"]) + "\n") if case.get("context") else "（暂无日程）\n"
    agenda += "用户未完成任务（[t数字] 是它的 id）：\n"
    tctx = subst(case["context"]) if case.get("context", "").find("[t") >= 0 else ""
    agenda += (tctx + "\n") if "[t" in case.get("context", "") else "（无）\n"
    if case.get("facts"):
        agenda += "你记住过的用户偏好：" + case["facts"] + "\n"
    return (f"{persona}\n当前时间：{now_line()}\n"
            f"日期参照（直接使用，不要自己加减）：{date_anchors()}\n\n{agenda}"
            "回复要求：简体中文、简洁友好、可少量 emoji。\n"
            "回答日程问题时优先引用上面的真实数据，不要编造。\n" + protocol)


TYPES = {"create_event", "create_task", "create_note", "update_event", "update_task",
         "update_note", "delete_event", "delete_task", "delete_note", "remember", "theme"}


def parse_actions(raw: str):
    """与 AiActions.split 同口径的简化版：fenced 块优先，退化到裸花括号"""
    acts, text = [], raw
    for m in re.finditer(r"```[a-zA-Z]*\s*([\s\S]*?)(?:```|$)", raw):
        text = text.replace(m.group(0), " ")
        try:
            o = json.loads(m.group(1).strip())
        except Exception:
            continue
        arr = o if isinstance(o, list) else o.get("actions") or o.get("events") or o.get("items") or []
        if isinstance(o, dict) and not arr and o.get("type"):
            arr = [o]
        for a in arr:
            if isinstance(a, dict) and a.get("type") in TYPES:
                acts.append(a)
    return acts, text


def call(model: str, key: str, system: str, msg: str) -> str:
    body = json.dumps({
        "model": model,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": msg}],
        "temperature": 0.3, "max_tokens": 1024,
        "enable_thinking": False, "reasoning": {"enabled": False},
    }).encode()
    req = urllib.request.Request(
        "https://openrouter.ai/api/v1/chat/completions", data=body,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                d = json.loads(r.read())
            c = d["choices"][0]["message"].get("content") or ""
            if c.strip():
                return re.sub(r"(?s)<think>.*?</think>", "", c).strip()
        except Exception as e:
            if attempt == 2:
                return f"__ERR__ {e}"
        time.sleep(2 * (attempt + 1))
    return "__ERR__ 空返回"


def hhmm(s: str) -> str:
    m = re.search(r"(\d{1,2})\s*[:：]\s*(\d{1,2})", str(s or ""))
    return f"{int(m.group(1)):02d}:{int(m.group(2)):02d}" if m else ""


def check(case: dict, acts, text: str):
    e, errs = case["expect"], []
    t = dt.date.today()

    def first(typ):
        return next((a for a in acts if a.get("type") == typ), None)
    if e.get("no_action") and acts:
        errs.append(f"应无动作，实出 {len(acts)} 条")
    if "count" in e and len(acts) != e["count"]:
        errs.append(f"动作数 {len(acts)} ≠ {e['count']}")
    if "no_type" in e and any(a.get("type") == e["no_type"] for a in acts):
        errs.append(f"不该出现 {e['no_type']}")
    for typ in e.get("types_include", []):
        if not first(typ):
            errs.append(f"缺 {typ}")
    a = first(e["type"]) if e.get("type") else None
    if e.get("type") and not a:
        errs.append(f"缺动作 {e['type']}")
    if a:
        if "id" in e and int(a.get("id") or -1) != e["id"]:
            errs.append(f"id {a.get('id')} ≠ {e['id']}")
        if "start" in e and hhmm(a.get("start")) != e["start"]:
            errs.append(f"start {a.get('start')} ≠ {e['start']}")
        if "end" in e and hhmm(a.get("end")) != e["end"]:
            errs.append(f"end {a.get('end')} ≠ {e['end']}")
        if "remind_before" in e and int(a.get("remind_before") or -1) != e["remind_before"]:
            errs.append(f"remind_before {a.get('remind_before')} ≠ {e['remind_before']}")
        if "remind_at" in e and hhmm(a.get("remind_at")) != e["remind_at"]:
            errs.append(f"remind_at {a.get('remind_at')} ≠ {e['remind_at']}")
        if e.get("all_day") and not a.get("all_day"):
            errs.append("应为全天")
        if "done" in e and bool(a.get("done")) != e["done"]:
            errs.append(f"done ≠ {e['done']}")
        if "title_contains" in e and e["title_contains"] not in (a.get("title") or ""):
            errs.append(f"标题缺「{e['title_contains']}」")
        if "fact_contains" in e and e["fact_contains"] not in (a.get("fact") or ""):
            errs.append(f"fact 缺「{e['fact_contains']}」")
        if e.get("accent_valid") and not re.match(r"^#[0-9a-fA-F]{6}$", a.get("accent") or ""):
            errs.append(f"accent 不合法：{a.get('accent')}")
        if "date" in e:
            got = str(a.get("date") or a.get("due") or "")
            d = e["date"]
            if "offset" in d:
                want = str(t + dt.timedelta(days=d["offset"]))
                if got != want:
                    errs.append(f"date {got} ≠ {want}")
            elif d.get("expr") == "next_monday":
                want = str(t + dt.timedelta(days=8 - t.isoweekday()))
                if got != want:
                    errs.append(f"date {got} ≠ 下周一 {want}")
            elif "md" in d:
                if not got.endswith(d["md"]):
                    errs.append(f"date {got} 不是 …-{d['md']}")
    if "mention" in e and e["mention"] not in text:
        errs.append(f"回复未提到「{e['mention']}」")
    if "not_mention" in e and e["not_mention"] in text:
        errs.append(f"回复泄露「{e['not_mention']}」")
    return errs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=default_model())
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    key = read_key()
    persona, protocol = extract_kt("PERSONA"), extract_kt("PROTOCOL")
    cases = json.loads((ROOT / "scripts/ai-eval-cases.json").read_text())["cases"]
    if args.only:
        pick = set(args.only.split(","))
        cases = [c for c in cases if c["id"] in pick]
    print(f"模型：{args.model} · 用例 {len(cases)} 条\n")
    passed = 0
    for c in cases:
        raw = call(args.model, key, build_system(c, persona, protocol), subst(c["msg"]))
        if raw.startswith("__ERR__"):
            print(f"✗ {c['id']} 请求失败：{raw[8:][:80]}")
            continue
        acts, text = parse_actions(raw)
        errs = check(c, acts, text)
        if errs:
            print(f"✗ {c['id']}（{c['group']}）：{'；'.join(errs)}")
            print(f"    ↳ {raw[:160].replace(chr(10), ' ')}")
        else:
            passed += 1
            print(f"✓ {c['id']}")
    rate = passed * 100 // max(1, len(cases))
    bar = 90
    print(f"\n通过 {passed}/{len(cases)}（{rate}%）· 达标线 {bar}%"
          + ("  —— 达标 ✅" if rate >= bar else "  —— 不达标 ❌ 不要上线"))
    return 0 if rate >= bar else 1


if __name__ == "__main__":
    sys.exit(main())
