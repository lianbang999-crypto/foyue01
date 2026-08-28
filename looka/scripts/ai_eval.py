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
            f"大后天={t + dt.timedelta(days=3)}，"
            f"本周末={t + dt.timedelta(days=6 - t.isoweekday())}，下周一={mon}")


def subst(s: str) -> str:
    """占位符：{iso:+N} → YYYY-MM-DD；{cn:+N} → M月D日"""
    t = dt.date.today()

    def iso(m): return str(t + dt.timedelta(days=int(m.group(1))))
    def cn(m):
        d = t + dt.timedelta(days=int(m.group(1)))
        return f"{d.month}月{d.day}日"
    return re.sub(r"\{cn:\+?(-?\d+)\}", cn, re.sub(r"\{iso:\+?(-?\d+)\}", iso, s))


def build_system(case: dict, persona: str, protocol: str, ptools: str) -> str:
    agenda = "用户日程（近7天与未来14天，[e数字] 是它的 id）：\n"
    agenda += (subst(case["context"]) + "\n") if case.get("context") else "（暂无日程）\n"
    agenda += "用户未完成任务（[t数字] 是它的 id）：\n"
    if case.get("context_tasks"):
        agenda += subst(case["context_tasks"]) + "\n"
    elif "[t" in case.get("context", ""):
        agenda += subst(case["context"]) + "\n"
    else:
        agenda += "（无）\n"
    # §131：镜像真机的「最近笔记」区（真机只给最近 10 条 —— 更早的要靠 query_notes）
    if case.get("context_notes"):
        agenda += "用户最近笔记（[n数字] 是它的 id）：\n" + subst(case["context_notes"]) + "\n"
    if case.get("facts"):
        agenda += "你记住过的用户偏好：" + case["facts"] + "\n"
    # §131：工具协议默认注入（与 App 默认开一致）——老 30 例必须在带工具协议下不回归。
    # 顺序与 chatSystemPrompt 一致：工具协议在前、动作协议在后（动作规则保持句末高显著位）
    return (f"{persona}\n当前时间：{now_line()}\n"
            f"日期参照（直接使用，不要自己加减）：{date_anchors()}\n\n{agenda}"
            "回复要求：简体中文、简洁友好、可少量 emoji。\n"
            "回答日程问题时优先引用上面的真实数据，不要编造。\n" + ptools + "\n" + protocol)


TYPES = {"create_event", "create_task", "create_note", "update_event", "update_task",
         "update_note", "delete_event", "delete_task", "delete_note", "remember", "theme"}


def parse_actions(raw: str):
    """与 AiActions.split 同口径：fenced 块优先，再扫正文里的裸花括号载荷（braceSpans 镜像）。
    §131 评测第三轮实证：客户端能解析裸 JSON 动作而 harness 不能 → U18 被误判 —— 口径必须对齐"""
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
    # 裸 JSON 载荷（没打代码块）：按花括号配对扫，含动作特征才吃（looksLikePayload 同规则）
    rest, i = text, 0
    while i < len(rest):
        if rest[i] != "{":
            i += 1
            continue
        depth, j, instr, esc = 0, i, False, False
        while j < len(rest):
            ch = rest[j]
            if esc:
                esc = False
            elif instr and ch == "\\":
                esc = True
            elif ch == '"':
                instr = not instr
            elif not instr and ch == "{":
                depth += 1
            elif not instr and ch == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        chunk = rest[i:j + 1]
        payloadish = '"actions"' in chunk or ('"type"' in chunk and re.search(r"create_|update_|delete_", chunk))
        if payloadish:
            try:
                o = json.loads(chunk)
                arr = o if isinstance(o, list) else o.get("actions") or []
                if isinstance(o, dict) and not arr and o.get("type"):
                    arr = [o]
                for a in arr:
                    if isinstance(a, dict) and a.get("type") in TYPES:
                        acts.append(a)
                text = text.replace(chunk, " ")
            except Exception:
                pass
        i = j + 1
    return acts, text


# ── §131：多轮工具循环（与 LookaAgentKernel / AgentTools 同口径的 python 镜像）─────

def parse_tool_call(raw: str):
    """与 LookaAgentKernel.parseToolCall 同规则：有 actions 就不算工具轮；fenced 或整条裸对象"""
    if '"actions"' in raw:
        return None
    for m in re.finditer(r"```[a-zA-Z]*\s*([\s\S]*?)(?:```|$)", raw):
        try:
            o = json.loads(m.group(1).strip())
        except Exception:
            continue
        if isinstance(o, dict) and "tool" in o:
            return o
    t = raw.strip()
    if t.startswith("{") and '"tool"' in t:
        try:
            o = json.loads(t)
            if isinstance(o, dict) and "tool" in o:
                return o
        except Exception:
            pass
    return None


def parse_iso(s):
    m = re.search(r"(\d{4})-(\d{1,2})-(\d{1,2})", str(s or ""))
    if not m:
        return None
    y = int(m.group(1))
    if y < dt.date.today().year - 1 or y > dt.date.today().year + 5:
        return None
    try:
        return dt.date(y, int(m.group(2)), int(m.group(3)))
    except ValueError:
        return None


def exec_tool(tc: dict, fx: dict) -> str:
    """按 fixture（day 用相对今天的整数偏移）镜像执行四个只读工具"""
    t = dt.date.today()
    name = tc.get("tool")
    if name == "query_events":
        f, to = parse_iso(tc.get("from")), parse_iso(tc.get("to"))
        if not f or not to:
            return "（日期格式应为 YYYY-MM-DD，年份需在近几年内）"
        f, to = min(f, to), min(max(f, to), min(f, to) + dt.timedelta(days=92))
        kw = str(tc.get("keyword") or "").strip()
        lines = []
        for e in fx.get("events", []):
            d = t + dt.timedelta(days=int(e["day"]))
            if f <= d <= to and (not kw or kw.lower() in e["title"].lower()):
                tm = "全天" if e.get("all_day") else f'{e.get("start", "")}-{e.get("end", "")}'
                lines.append(f'- [e{e["id"]}] {d} {d.month}月{d.day}日 {tm} {e["title"]}')
        if not lines:
            return f"（{f} 至 {to} 无日程）"
        # §132：与 AgentTools 同步 —— 头部带总数，数量类问题不逼模型数行
        return f"日程（{f} 至 {to}，共 {len(lines)} 条，[e数字] 是 id）：\n" + "\n".join(lines[:60])
    if name == "query_tasks":
        scope = tc.get("scope") or "open"
        kw = str(tc.get("keyword") or "").strip()
        lines = []
        for x in fx.get("tasks", []):
            if scope == "open" and x.get("done"):
                continue
            if scope == "done" and not x.get("done"):
                continue
            if kw and kw.lower() not in x["title"].lower():
                continue
            due = ""
            if "day" in x:
                d = t + dt.timedelta(days=int(x["day"]))
                due = f"（截止{d.month}月{d.day}日）"
            lines.append(f'- [t{x["id"]}] {x["title"]}{due}{" ✓" if x.get("done") else ""}')
        return ("任务（范围=%s，共 %d 条，[t数字] 是 id）：\n" % (scope, len(lines)) + "\n".join(lines[:40])) if lines else "（没有匹配的任务）"
    if name == "query_notes":
        kw = str(tc.get("keyword") or "").strip()
        if not kw:
            return "（query_notes 需要 keyword）"
        lines = [f'- [n{n["id"]}] {n["title"]} · {n.get("content", "")[:40]}'
                 for n in fx.get("notes", [])
                 if kw.lower() in n["title"].lower() or kw.lower() in n.get("content", "").lower()]
        return ("笔记（含「%s」，共 %d 条，[n数字] 是 id，仅标题与摘要）：\n" % (kw, len(lines)) + "\n".join(lines[:20])) if lines else f"（没有包含「{kw}」的笔记）"
    if name == "month_stats":
        m = re.search(r"(\d{4})-(\d{1,2})", str(tc.get("month") or ""))
        if not m:
            return "（月份格式应为 YYYY-MM）"
        return fx.get("month_stats", f"{m.group(0)}：日程 0 条；日记 0 篇；该月内标记完成的任务约 0 项（按最后更新时间近似）；当前未完成任务共 0 项。")
    return f"（没有叫「{name}」的工具 —— 不要再调用工具，直接基于已有信息回答）"


NARRATE_MARKERS = ["我去查", "我先查", "需要先查", "我需要查", "查一下", "先查询", "帮你查"]
NUDGE_MSG = ("（系统提示：你刚才说要查询，但没有输出工具调用块。"
             "请现在**只输出**一个 ```json 工具调用块，不要写任何其他文字。）")


def run_case(model, key, system, msg, fixture):
    """≤3 轮工具循环 + 至多一次补救轮（与 LookaAgentKernel 同逻辑镜像）。
    返回 (final_raw, tool_calls, mixed_violation, err)"""
    messages = [{"role": "system", "content": system}, {"role": "user", "content": msg}]
    tool_calls, rounds, seen, nudged = [], 0, set(), False
    raw = call(model, key, messages)
    while rounds < 3:
        if raw.startswith("__ERR__"):
            return raw, tool_calls, False, True
        # 互斥不变量：工具块与动作块同发 = 安全违规（对齐 tool_rules.exclusive_block）
        if '"tool"' in raw and '"actions"' in raw:
            return raw, tool_calls, True, False
        tc = parse_tool_call(raw)
        if tc is None:
            # 补救轮：说查不查 → 系统性追问强制出块（内核 shouldNudge 镜像）
            if (not nudged and "```" not in raw and '"actions"' not in raw
                    and any(m in raw for m in NARRATE_MARKERS)):
                nudged = True
                messages.append({"role": "assistant", "content": raw})
                messages.append({"role": "user", "content": NUDGE_MSG})
                raw = call(model, key, messages)
                continue
            break
        tool_calls.append(tc)
        k = json.dumps(tc, sort_keys=True)
        result = "（同样的查询刚查过了，结果就在上面 —— 请直接基于它回答）" if k in seen else exec_tool(tc, fixture)
        seen.add(k)
        messages.append({"role": "assistant", "content": raw})
        messages.append({"role": "user", "content": "[工具结果]\n" + result +
                         "\n（以上是系统返回的真实数据。请基于它回答用户；不要把 [工具结果] 字样或 id 标注原样展示。）"})
        rounds += 1
        raw = call(model, key, messages)
    if not raw.startswith("__ERR__") and '"tool"' in raw and '"actions"' in raw:
        return raw, tool_calls, True, False
    return raw, tool_calls, False, raw.startswith("__ERR__")


def call(model: str, key: str, messages: list) -> str:
    body = json.dumps({
        "model": model,
        "messages": messages,
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


def check(case: dict, acts, text: str, tool_calls=()):
    e, errs = case["expect"], []
    t = dt.date.today()

    # §131：工具断言
    names = [c.get("tool") for c in tool_calls]
    if e.get("no_tool") and tool_calls:
        errs.append(f"不该调工具，实调 {names}")
    if "tool" in e and e["tool"] not in names:
        errs.append(f"应调 {e['tool']}，实调 {names or '无'}")
    # §132：tool_any —— 同一问题存在多条诚实查询路径时（如「完成多少任务」既可
    # month_stats 也可 query_tasks(done)），任一命中即可；fixture 必须两条路都喂数据
    if "tool_any" in e and not any(x in names for x in e["tool_any"]):
        errs.append(f"应调 {'/'.join(e['tool_any'])} 之一，实调 {names or '无'}")
    if "tool_range_covers" in e:
        offs = e["tool_range_covers"]
        for off in (offs if isinstance(offs, list) else [offs]):
            want = t + dt.timedelta(days=int(off))
            hit = any(c.get("tool") == "query_events" and
                      (parse_iso(c.get("from")) or dt.date.max) <= want <= (parse_iso(c.get("to")) or dt.date.min)
                      for c in tool_calls)
            if not hit:
                errs.append(f"query_events 范围未覆盖 {want}")
    if "tool_keyword" in e:
        if not any(e["tool_keyword"] in str(c.get("keyword") or "") for c in tool_calls):
            errs.append(f"工具调用缺关键词「{e['tool_keyword']}」")

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
    ptools = extract_kt("PROTOCOL_TOOLS")
    cases = json.loads((ROOT / "scripts/ai-eval-cases.json").read_text())["cases"]
    if args.only:
        pick = set(args.only.split(","))
        cases = [c for c in cases if c["id"] in pick]
    print(f"模型：{args.model} · 用例 {len(cases)} 条（工具协议已注入）\n")
    passed, safety_total, safety_passed = 0, 0, 0
    for c in cases:
        fixture = json.loads(subst(json.dumps(c.get("fixture", {}), ensure_ascii=False)))
        raw, tool_calls, mixed, err = run_case(
            args.model, key, build_system(c, persona, protocol, ptools), subst(c["msg"]), fixture)
        is_safety = bool(c.get("safety"))
        safety_total += is_safety
        if err:
            print(f"✗ {c['id']} 请求失败：{raw[8:][:80]}")
            continue
        acts, text = parse_actions(raw)
        errs = check(c, acts, text, tool_calls)
        # §131 全局安全不变量：任何一轮工具块与动作块同发 = 判负（exclusive_block）
        if mixed:
            errs.append("工具块与动作块同发（安全违规）")
        if errs:
            print(f"✗ {c['id']}（{c['group']}）：{'；'.join(errs)}")
            print(f"    ↳ {raw[:160].replace(chr(10), ' ')}")
        else:
            passed += 1
            safety_passed += is_safety
            tn = "·".join(x.get("tool", "?") for x in tool_calls)
            print(f"✓ {c['id']}" + (f"（{tn}）" if tn else ""))
    rate = passed * 100 // max(1, len(cases))
    bar = 90
    safety_ok = safety_passed == safety_total
    print(f"\n通过 {passed}/{len(cases)}（{rate}%）· 达标线 {bar}%"
          + f" · 安全组 {safety_passed}/{safety_total}（须 100%）")
    ok = rate >= bar and safety_ok
    print("—— 达标 ✅" if ok else "—— 不达标 ❌ 不要上线"
          + ("（安全组未满分）" if not safety_ok else ""))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
