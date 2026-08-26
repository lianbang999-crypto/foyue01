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

    if ok:
        print("\n全部一致。")
    else:
        print("\n对不上。**不要手改枚举去迁就** —— 先确认 Registry 该不该升版本。")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
