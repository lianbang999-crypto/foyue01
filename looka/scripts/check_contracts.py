#!/usr/bin/env python3
"""
§107 C：枚举与机器合同的一致性对账。

改了 JSON 没改枚举、或者反过来，这里都会红。
这条纪律不是形式主义 —— 主题包是**外部（含 AI）生成**的，
生成端照 Registry 出图、渲染端照枚举取图，两边一旦对不上，
错误要等一整套图生出来、装进去、看到空白才暴露出来。
在这里挡住，成本是一秒。

用法：  python3 scripts/check_contracts.py
退出码：0 = 一致；1 = 不一致（并打印差集）
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/looka/app/ui/theme/Skin.kt"
TOKENS_KT = ROOT / "app/src/main/java/com/looka/app/ui/theme/Tokens.kt"


def enum_values(src: str, enum_name: str) -> set:
    """抓 `enum class X(...) { A("a.b"), ... }` 里所有字符串字面量"""
    m = re.search(r"enum class %s\([^)]*\)\s*\{(.*?)\n\}" % enum_name, src, re.S)
    if not m:
        sys.exit(f"✗ 在 {KT.name} 里找不到 enum class {enum_name}")
    # 只取枚举项那一段（companion object 之前）
    body = m.group(1).split("companion object")[0]
    return set(re.findall(r'"([^"]+)"', body))


def contract_ids(rel: str, array_field: str, id_field: str) -> set:
    data = json.loads((ROOT / rel).read_text())
    return {x[id_field] for x in data[array_field]}


def compare(label: str, contract: set, enum: set) -> bool:
    if contract == enum:
        print(f"✓ {label}：{len(enum)} 项一致")
        return True
    print(f"✗ {label} 不一致")
    if contract - enum:
        print(f"    合约有、枚举缺：{sorted(contract - enum)}")
    if enum - contract:
        print(f"    枚举有、合约缺：{sorted(enum - contract)}")
    return False


def main() -> int:
    src = KT.read_text()
    ok = True
    ok &= compare(
        "SkinSlot ↔ slot-registry.v1",
        contract_ids("docs/contracts/slot-registry.v1.json", "slots", "slot_id"),
        enum_values(src, "SkinSlot"),
    )
    ok &= compare(
        "IconId ↔ icon-registry.v1",
        contract_ids("docs/contracts/icon-registry.v1.json", "icons", "icon_id"),
        enum_values(src, "IconId"),
    )

    # 语义色令牌：合约里是 snake_case 的必填键，Kotlin 侧是驼峰字段
    schema = json.loads((ROOT / "docs/contracts/theme-tokens.schema.json").read_text())
    need = set(schema["properties"]["semantic"]["required"])
    fields = set(
        re.findall(
            r"val\s+(\w+):\s*Color",
            re.search(r"data class LookaTokens\((.*?)\n\)", TOKENS_KT.read_text(), re.S).group(1),
        )
    )
    camel = {re.sub(r"_(\w)", lambda m: m.group(1).upper(), k) for k in need}
    ok &= compare("LookaTokens ↔ theme-tokens.schema", camel, fields)

    # §117 E3：皮肤包合同（theme-package.v1）三方对账 ——
    # ① JSON 可解析且内嵌引用 theme-tokens；② Web 语义变量 --lk-* 与合同键一一对应。
    # 皮肤包是外部（含 AI）按合同生成的，Web 端少接一个变量，装包后就有一块颜色不跟。
    pkg = json.loads((ROOT / "docs/contracts/theme-package.v1.json").read_text())
    assert pkg["properties"]["schema_version"]["const"] == "1.0"
    css = (ROOT / "server/public/style.css").read_text()
    css_vars = set(re.findall(r"--lk-([a-z-]+):", css))
    css_semantic = {v.replace("-", "_") for v in css_vars if not v.startswith("skin_") and v not in ("skin",)}
    css_semantic = {v for v in css_semantic if v not in ("skin_top", "skin_bottom")}
    ok &= compare("Web --lk-* ↔ theme-tokens.schema", need, css_semantic)

    # §119 T1：经济合同对账 —— worker 的 ANTLER 常量必须与 economy.v1.json 一致
    #（文案与页面读合同，服务端读常量，两边漂移 = 用户看到的数字与实际扣的不一样）
    eco = json.loads((ROOT / "docs/contracts/economy.v1.json").read_text())
    wk = (ROOT / "server/src/worker.js").read_text()
    m = re.search(r"const ANTLER = \{(.*?)\n\};", wk, re.S)
    assert m, "worker 里找不到 ANTLER 常量"
    seg = m.group(1)
    ok2 = True
    gm = re.search(r"grant:\s*\{\s*free:\s*(\d+),\s*pro:\s*(\d+)", seg)
    if not gm or int(gm.group(1)) != eco["grant_daily"]["free"] or int(gm.group(2)) != eco["grant_daily"]["pro"]:
        print("✗ 每日发放：worker ≠ economy.v1"); ok2 = False
    cm = re.search(r"chat:\s*(\d+)", seg)
    if not cm or int(cm.group(1)) != eco["cost"]["chat"]:
        print("✗ 对话成本：worker ≠ economy.v1"); ok2 = False
    if "cap:" in seg and eco["balance_cap"] is None:
        print("✗ economy.v1 已定无上限，worker 仍有 cap"); ok2 = False
    if ok2: print("✓ ANTLER ↔ economy.v1：发放/成本/无上限一致")
    ok &= ok2

    # §119 T1：商品对账 —— worker SHOP_ITEMS 与 catalog.v1.json 一致
    cat = json.loads((ROOT / "docs/contracts/catalog.v1.json").read_text())
    ok3 = True
    for it in cat["items"]:
        pm = re.search(r"id:\s*'%s'[^}]*price:\s*(\d+)[^}]*count:\s*(\d+)" % re.escape(it["id"]), wk)
        if not pm:
            print(f"✗ 商品 {it['id']} 不在 worker SHOP_ITEMS"); ok3 = False
        elif int(pm.group(1)) != it["price_antler"] or int(pm.group(2)) != it["count"]:
            print(f"✗ 商品 {it['id']} 价格/数量：worker ≠ catalog.v1"); ok3 = False
    if ok3: print(f"✓ SHOP_ITEMS ↔ catalog.v1：{len(cat['items'])} 件商品一致")
    ok &= ok3

    # §126 D1：AI 动作协议三方对账 —— 合同 types ↔ AiActions.parseActions 白名单
    # ↔ 提示词 PROTOCOL 声明 ↔ 评测集用例。动作协议是模型按提示词生成、客户端按白名单
    # 解析的，三边漂移的后果是"模型输出了、客户端默默丢了"，用户只看到"小鹿没反应"。
    aic = json.loads((ROOT / "docs/contracts/ai-actions.v1.json").read_text())
    types = set(aic["types"])
    akt = (ROOT / "app/src/main/java/com/looka/app/ai/AiActions.kt").read_text()
    wl = re.search(r"if \(type !in setOf\((.*?)\)\s*\n?\s*\)", akt, re.S)
    assert wl, "AiActions.kt 里找不到解析白名单 setOf(...)"
    kt_types = set(re.findall(r'"([a-z_]+)"', wl.group(1)))
    ok &= compare("ai-actions.v1 ↔ AiActions 解析白名单", types, kt_types)
    proto = re.search(r'private val PROTOCOL = """(.*?)"""', akt, re.S)
    assert proto, "AiActions.kt 里找不到 PROTOCOL"
    missing_in_proto = {t for t in types if f'"{t}"' not in proto.group(1)}
    if missing_in_proto:
        print(f"✗ PROTOCOL 未声明的动作类型：{sorted(missing_in_proto)}"); ok = False
    else:
        print(f"✓ PROTOCOL 声明 ↔ ai-actions.v1：{len(types)} 类齐全")
    cases_p = ROOT / "scripts/ai-eval-cases.json"
    if cases_p.exists():
        cases = json.loads(cases_p.read_text())
        bad = {c["expect"].get("type") for c in cases["cases"]
               if c["expect"].get("type") and c["expect"]["type"] not in types}
        if bad:
            print(f"✗ 评测集里有合同外类型：{sorted(bad)}"); ok = False
        else:
            print(f"✓ 评测集 {len(cases['cases'])} 例类型全在合同内")

    # §128：定价合同对账 —— pricing.v1 ↔ worker PRICING 常量 ↔ 双端页面文案。
    # 四套口径打过架（母档 §8 审计），从此只许一处出数字。
    pr = json.loads((ROOT / "docs/contracts/pricing.v1.json").read_text())
    okp = True
    pm = re.search(r"const PRICING = \{(.*?)\n\};", wk, re.S)
    assert pm, "worker 里找不到 PRICING 常量"
    seg2 = pm.group(1)
    cm2 = re.search(r"cny:\s*\{\s*month:\s*([\d.]+),\s*year:\s*([\d.]+)", seg2)
    sub = pr["subscription"]
    if not cm2 or float(cm2.group(1)) != sub["cny"]["month"] or float(cm2.group(2)) != sub["cny"]["year"]:
        print("✗ CNY 订阅价：worker ≠ pricing.v1"); okp = False
    um = re.search(r"usd:\s*\{\s*month:\s*([\d.]+),\s*year:\s*([\d.]+)", seg2)
    if not um or float(um.group(1)) != sub["usd"]["month"] or float(um.group(2)) != sub["usd"]["year"]:
        print("✗ USD 订阅价：worker ≠ pricing.v1"); okp = False
    fbm = re.search(r"founder_buyout_cny:\s*([\d.]+)", seg2)
    buyout = next(x for x in pr["stages"] if x["id"] == "founder_buyout")
    if not fbm or float(fbm.group(1)) != buyout["price_cny"]:
        print("✗ 买断价：worker ≠ pricing.v1"); okp = False
    for pk2 in pr["antler_packs"]:
        if not re.search(r"id:\s*'%s',\s*amount:\s*%d,\s*cny:\s*%d" % (pk2["id"], pk2["amount"], pk2["price_cny"]), seg2):
            print(f"✗ 鹿角包 {pk2['id']}：worker ≠ pricing.v1"); okp = False
    webidx = (ROOT / "server/public/index.html").read_text()
    if f"{sub['cny']['month']} 元/月" not in webidx or f"{sub['cny']['year']} 元/年" not in webidx:
        print("✗ Web 定价文案 ≠ pricing.v1"); okp = False
    app_sub = (ROOT / "app/src/main/java/com/looka/app/ui/more/ExtraScreens.kt").read_text()
    if f"¥{sub['cny']['month']}" not in app_sub or f"¥{sub['cny']['year']}" not in app_sub:
        print("✗ App 方案页价格 ≠ pricing.v1"); okp = False
    if okp:
        print("✓ PRICING ↔ pricing.v1 ↔ 双端文案：订阅/买断/鹿角包一致")
    ok &= okp

    if ok:
        print("\n全部一致。")
    else:
        print("\n对不上。**不要手改枚举去迁就** —— 先确认 Registry 该不该升版本。")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
