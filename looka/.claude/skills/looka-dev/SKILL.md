---
name: looka-dev
description: >-
  Looka（可爱版九色鹿手帐）项目的开发工作流：母档证据分级、计划先行、双端同步、
  执行清单勾选纪律、发版流水线、查证纪律。在 looka 目录下做任何功能开发、
  bug 修复、对齐 Lifebear、发版或复核文档时使用。
metadata:
  version: "1.0.0"
  project: looka
---

# Looka 开发工作流

这套流程是 8 天 33 个版本踩出来的，每条规矩背后都有一次真实事故。
背景见 `docs/DEV-HISTORY.md`。

## 项目速览

| 项 | 值 |
|---|---|
| 主目录 | `/Users/bincai/Downloads/foyue/looka` |
| Android | Kotlin 2.0.21 · Compose M3 · Room（**kapt 不是 KSP**）· minSdk 26 / target 35 |
| 网页 | `looka.foyue.org` · Cloudflare Worker + D1 + R2 · 原生 JS |
| 计划书 | `docs/FEATURE-PLAN.md`（按 §N 编号追加） |
| 执行清单 | `docs/EXECUTION.md`（唯一勾选处） |
| 开发史 | `docs/DEV-HISTORY.md` |

构建命令（`JAVA_HOME` 必须显式给）：

```bash
export JAVA_HOME=/Users/bincai/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home
GRADLE=/Users/bincai/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle
"$GRADLE" -p /Users/bincai/Downloads/foyue/looka compileReleaseKotlin -q   # 只验编译
"$GRADLE" -p /Users/bincai/Downloads/foyue/looka assembleRelease -q        # 出包（>5min，用后台任务）
```

---

## 一、铁律（用户明确要求，不可协商）

1. **不开子智能体** —— 主会话直接完成，不派 worker / 不用多智能体编排。
2. **每次修改 App 与网页同步** —— 改一端就要问「另一端呢」。仅一端存在的形态要在计划里写明理由。
3. **尽量让利于用户** —— 定价与功能分档的最高原则，优先级高于所有商业条目。
4. **先上 GitHub 再部署** —— `scripts/release.sh` 已把 push 设为前置，失败即中止。
5. **计划先行** —— 用户原话：「写好计划，照着计划走，就不会有缺漏。逐条跟进逐项完成。」

---

## 二、计划先行的具体做法

### 写计划

追加到 `docs/FEATURE-PLAN.md` 末尾，用递增的中文数字章节号：

```markdown
## 八十四、§84 规划（日期）：一句话主题

### 一、背景 / 用户诉求
### 二、🔴 根因诊断（代码实证，不是猜测）
### 三、逐条对表（规格 | 我们现在 | 处置）
### 四、批次
```

**要点**：
- 根因必须有代码实证。写「可能是 X」和写「实查 `文件:行号` 是 X」价值差一个数量级。
- 对表用三列：规格怎么说 / 我们现在怎样 / 处置代号。代号后面进执行清单。
- **主动偏离规格要单列一节记录在案**，写清为什么。以后回看不至于当成疏漏。

### 什么时候只写不执行

用户说「先写计划，不执行」时**严格遵守**。这种时候的交付物是判断，不是代码。
写完汇报，停下。

---

## 三、执行清单勾选纪律

`docs/EXECUTION.md` 是**唯一**勾选处。三种状态含义严格区分：

| 标记 | 含义 |
|---|---|
| `[ ]` | 未做 |
| `[~]` | **已实现，未在真机实测** |
| `[x]` | **实测通过** —— 只有真机验证过才能打 |

新批次追加一节：

```markdown
## P34 · §84 主题（vX.Y.Z，日期）

| | 项 | 状态 |
|---|---|---|
| A1 | 具体做了什么 | [~] |
```

**定期回勾**：做完后续批次时，检查早先条目是否已被后来的改动覆盖。
曾经一次性回勾出 6 条「其实早做完了但没回来打勾」的。

---

## 四、母档与证据分级

用户会持续提供 Lifebear 母档（.docx）与实机截图/录屏。**证据分级不能混用**：

| 级别 | 含义 | 用法 |
|---|---|---|
| A | 官方规则 | 能力边界、业务语义 |
| B | Lifebear 实机确认 | 可直接按保真度实现 |
| L | **Looka 当前 Build** | 只用于差距审查与回归，**不能反向证明 B** |
| C | clean-room 推导 | 按「推荐默认」实现，保留可配置 |
| R | 外部参考 | 只继承体验方向，不得宣称为实机能力 |

**读 .docx 的方法**（无需第三方库）：

```python
import zipfile, re, html
z = zipfile.ZipFile('looka所有文档/xxx.docx')
x = z.read('word/document.xml').decode('utf-8')
t = html.unescape(re.sub(r'<[^>]+>', '', re.sub(r'</w:p>', '\n', x)))
```

**尺寸换算**：录屏截图 1136×2690 显示为 845×2000 时，1 显示 px ≈ 0.4485dp。
**不要把录屏像素直接写成 dp 常量**，一律换算成「×列宽」这类相对值。

---

## 五、查证纪律（每条都有事故背景）

1. **grep 对大文件会给假阴性**。65KB 的 `worker.js` 搜 `ANTLER` 返回 0，实际 14 处。
   → **结论性判断一律用 python 复验**：
   ```python
   s = open('server/src/worker.js', encoding='utf-8').read(); print(s.count('ANTLER'))
   ```

2. **没有实机图不下结论**。曾断言「我们点日期整页跳转，日历上下文全丢」——
   真实截图显示我们早就有底部面板加四模式标签。

3. **删入口前查全部可达路径**，确认剩 ≥1 条。AI 入口曾被两次「合理删除」删没。

4. **UI 对了不等于数据对了**。详情页显示「日期已改」可能只是它信了传入参数，
   后台压根没动。改数据的功能要查 DAO 层。

5. **参数调不动时，八成不是参数问题**。菜单遮挡贴纸调了两轮间隙都没用，
   真因是坐标系错位。

6. **读图推断要标注为推断**，等实机证据再升级。曾把误读写进规格，造出一个盖住贴纸的气泡。

---

## 六、双端同步检查表

改任何用户可见行为，逐项过一遍：

- [ ] Android：`app/src/main/java/com/looka/app/`
- [ ] 网页：`server/public/app.js` + `style.css` + `index.html`
- [ ] 服务端：`server/src/worker.js`（涉及数据/接口时）
- [ ] i18n：中文源串**就是 key**，流程见下
- [ ] Room 迁移：改实体必须加 `MIGRATION_x_y` 并在 `LookaApp.kt` 注册

i18n 流程：

```bash
python3 scripts/build_i18n.py          # 报缺译
# 缺的补进 i18n/dict-en.tsv（制表符分隔：中文<TAB>English）
python3 scripts/build_i18n.py          # 必须「缺译 0 条」
```

---

## 七、发版流水线

```bash
# 1 改版本号
sed -i '' 's/versionCode = N/versionCode = N+1/; s/versionName = "x"/versionName = "y"/' app/build.gradle.kts

# 2 i18n 必须 0 缺译；JS 必须过语法检查
python3 scripts/build_i18n.py && node --check server/public/app.js

# 3 编译验证
"$GRADLE" -p . compileReleaseKotlin -q

# 4 提交 + 推 GitHub（前置，失败即止）
git add app docs server/public i18n && git commit -m "feat: vX.Y.Z —— §N 摘要"
git push looka main

# 5 发版（内含：push → 构建 → R2 → version.json → 静态资源盖章 → deploy）
bash scripts/release.sh "面向用户的更新说明"

# 6 线上验证 + 盖章提交
curl -s https://looka.foyue.org/version.json | python3 -c "import json,sys;v=json.load(sys.stdin);print(v['versionName'],v['versionCode'])"
git add server/public/version.json server/public/index.html server/public/sw.js
git commit -m "chore: vX.Y.Z 发版盖章" && git push looka main
```

**网络抖动是常态**：GitHub 推送和 Cloudflare 都会随机 `fetch failed`。
用重试循环，不要一次失败就报告失败：

```bash
for i in 1 2 3 4 5; do
  npx wrangler deploy 2>&1 | grep -q "Current Version ID" && { echo ok; break; }
  sleep 25
done
```

**踩过的坑**：
- `assembleRelease` 常超 10 分钟 → 用后台任务跑，别让前台超时打断
- `.zshrc` 里的 `CLOUDFLARE_API_TOKEN` **没有 R2 写权限**（401）；实际走 OAuth，
  不要显式 export 那个 token
- 父仓库里有嵌套 git 仓库，`git add -A` 会加进 gitlink → **只 add 明确路径**

---

## 八、代码风格

- 注释用中文，**写「为什么」不写「做了什么」**。特别是记录根因和主动偏离：
  ```kotlin
  // §74 P0-2：不再用 rowHpx 浮点算式反推行号（面板 inset 重排网格时存在陈旧窗口，
  // 实测把 12/17 算成 12/10 整整错一周）。改问 LazyColumn 本帧真实布局。
  ```
- 尺寸用相对量（`maxWidth * 0.42f`），不硬编码 px
- Material3 主题级覆写优先于逐处修改 —— 例如 `shapes.extraLarge` 一行改掉全站 33 个弹窗

---

## 九、汇报方式

用户看的是结论，不是过程。

- **先说结果**：第一句回答「发生了什么 / 发现了什么」
- **根因写清楚**，尤其是「我之前判断错了」这类要主动讲，不要藏
- **不要用箭头链和缩写**（`A → B → fails`），用完整句子
- 表格只放可枚举的事实，解释放在正文
- 发版后给**真机验证清单** —— 我们无法代验的那部分

---

## 十、常见任务索引

| 任务 | 入口 |
|---|---|
| 日历主页 | `ui/calendar/CalendarScreen.kt`（月视图 + 贴纸画布 + Popover） |
| 周/日时间轴 | `ui/calendar/TimelineView.kt` |
| 日程编辑器 | `ui/event/EventEditorScreen.kt`（父编辑器，含三模式切换） |
| 贴纸选择器 | `ui/common/StickerPicker.kt` |
| 待办 | `ui/todo/TodoScreen.kt` + `TaskListScreens.kt` |
| 更多页 | `ui/more/MoreScreen.kt` + `ExtraScreens.kt` |
| 数据实体/迁移 | `data/Entities.kt` · `data/LookaDb.kt` · `data/Daos.kt` |
| 状态与业务 | `vm/LookaViewModel.kt` |
| 网页全部逻辑 | `server/public/app.js`（单文件） |
| 服务端 | `server/src/worker.js`（单文件，含鹿角/AI/同步/支付） |
