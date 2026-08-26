# Lifebear UI/UX 全局设计研究与数据基线

> 文档状态：研究母档，不代表 Looka 已实施或已发布
>
> 研究日期：2026-08-26
>
> 研究对象：项目内现有 Lifebear 母档、实机截图与录屏
>
> 当前边界：只研究 Lifebear；不以 Looka 当前界面反向证明 Lifebear，不在本文做 Looka 差距评分

## 0. 先说结论

Lifebear 的视觉品质不靠大圆角、卡片堆叠、渐变或品牌色铺满页面，而靠一套非常稳定的“安静工具骨架”：

1. 页面长期保持白底、少边框、浅分隔、大留白；颜色只承担日期、分类、状态和个性化语义。
2. 核心对象各自拥有合适的编辑器，不为了代码复用而把 Event、Task、Note、Diary 做成同一张万能表单。
3. 高频动作直接完成；低频动作收进 More；危险动作才进入阻断式 Dialog。
4. 弹窗宽度高度并非统一模板。宽度高度由任务类型、内容量、键盘状态和决策成本共同决定。
5. Lifebear 实机 Dialog 是克制的小圆角，测得约 2dp；不是常见的 16–28dp 大圆角卡片。
6. 视图切换 Bottom Sheet 在本批实机证据中为全宽直角面板。此前规格中的 16–20dp 是 clean-room 建议，不是 Lifebear 实机定值。
7. 系统用清晰的层级替代说明文字：Inline、Menu、Dialog、Bottom Sheet、Full-page Editor 各自只做适合自己的事。
8. 交互的真正统一单位不是“所有页面长得一样”，而是 pressed、选择、提交、取消、返回、草稿、焦点和恢复规则一致。

一句话概括：**Calm Utility 是骨架，Emotional Warmth 是少量但有意义的情绪节点。**

---

## 1. 证据边界

### 1.1 证据等级

| 级别 | 含义 | 本文用法 |
|---|---|---|
| A | Lifebear 官方公开规则 | 只沿用既有母档已经登记的业务边界；本次未重新联网复核 |
| B | Lifebear 实机截图或录屏 | 可作为布局、状态、层级、相对比例和交互链路事实 |
| B-测 | 从 B 级画面做的像素或比例测量 | 比例可信度高于绝对 dp；压缩、设备密度和系统字体会引入误差 |
| C | clean-room 实现建议 | 为 Looka 或其他实现提供的推荐值，不得写成 Lifebear 原生定值 |
| L | Looka 当前 Build | 只用于以后差距审查；本文不拿它证明 Lifebear |

### 1.2 本次实际盘点

- 候选实机/对照素材：203 张图片、39 段 MP4。
- 视频中存在 2 组完全相同的重复文件，去重后为 37 段独立录屏。
- 已逐目录制作并检查联系表：`参考组件图标/`、`0821/`、`0822/`、`0824/`、`0826/`、`0833/`。
- 已将明显属于 Looka 当前 Build、插画素材、营销海报的画面排除出 Lifebear 实机证明。
- 已重点复看 4 条链路录屏：Settings、Note/List、Task/List、Calendar/Event/Sticker。
- 已读取最新版 Product DNA、Calendar、Popup、Sticker 母档，以及 Task 和 Note/Diary 的既有审计。

### 1.3 测量基线

本批主截图多为 `1136 × 2690px`。项目已有换算记录为：原图显示到 `845 × 2000px` 后，1 个显示像素约为 `0.4485dp`。因此：

```text
1 个原图像素 ≈ 845 / 1136 × 0.4485dp ≈ 0.3336dp
1136px 屏幕宽 ≈ 379dp
```

本文采用以下纪律：

- 优先写屏幕占比、列宽比例、内容区比例。
- dp 只写“约值”，用于判断量级，不用于直接硬编码。
- `540 × 1280px` 录屏只使用相对比例，不从视频像素直接冻结 dp。
- 字体只能估计语义级别和范围；JPEG 无法证明原生字体的精确 pt/sp。

---

## 2. 产品与视觉 DNA

### 2.1 Calm Utility：安静的工具骨架

| 维度 | Lifebear 做法 | 设计作用 |
|---|---|---|
| 背景 | 纯白或近白；几乎不使用大面积彩色容器 | 降低长期使用疲劳 |
| 组织 | 行、细分隔、标题和留白；极少依赖卡片边框 | 让信息结构比容器更突出 |
| 选择 | 黑色实心、黑线、灰底、checkmark | 不依赖主题色也能识别 |
| 色彩 | Calendar/Source 色点、周日红、周六蓝、Star 橙 | 颜色只做语义，不做装饰铺陈 |
| 强动作 | App Bar 右侧黑色 Save、底部中央黑色 `+` | 强操作锚点少而稳定 |
| 次动作 | 文字按钮、行点击、More menu | 不与主动作竞争 |
| 商业层 | More/Plan/Shop 独立于核心对象编辑 | 不污染 Event/Task/Note 的心智 |

### 2.2 Emotional Warmth：只在需要时出现温度

- 空状态使用蓝色熊插画和明确的创建引导，不用冷冰冰的“0 items”。
- Sticker 和 Theme 是个人空间的一部分，不是页面骨架上的持续装饰。
- Calendar 本身保持安静，情绪内容由用户放置的 Sticker、日记和主题产生。
- 广告有固定槽位，不盖住底部导航、中央 `+` 和数据操作区。

### 2.3 一条最重要的反例规则

不要把 Lifebear 复刻成以下样子：

- 每一组都装进 20–28dp 大圆角卡片；
- 每个页面都有渐变、发光、半透明玻璃；
- 所有按钮都用品牌色实心；
- Event、Task、Note、Diary 共用一张“万能表单”；
- 为了显得可爱，在高频工具操作中持续加入弹跳、漂浮和装饰。

这些做法会破坏 Lifebear 最核心的克制感。

---

## 3. 全局几何与间距基线

### 3.1 推荐冻结的结构 Token

以下为 B 级画面与既有母档共同支持的 C 级实现基线：

| Token | 基线 | 说明 |
|---|---:|---|
| `space.1` | 4dp | 最小节奏单位 |
| `space.2` | 8dp | 图标与文字、紧凑组内间距 |
| `space.3` | 12dp | 行内次级信息、紧凑区块 |
| `space.4` | 16dp | 页面常规水平边距 |
| `space.6` | 24dp | Dialog 内边距、区块分隔 |
| `touch.min` | 44 × 44dp | 视觉图标可小，点击热区不能小 |
| `appbar.content` | 44–56dp | 不含系统安全区 |
| `form.row` | 48–56dp | 两行摘要可到 64dp |
| `bottomnav` | 约 58dp | 不含系统导航安全区 |
| `global.plus` | 约 48dp | 完整圆形，底部最强动作锚点 |
| `divider` | 约 1px / 1dp | 低对比语义线，避免厚边框 |
| `screen.padding.x` | 约 16dp | 全页内容常规边距 |
| `dialog.padding.x` | 约 24dp | 实测标题起点与面板边缘约 24–26dp |

### 3.2 Calendar 的相对几何

| 项 | 规则 |
|---|---|
| 日期列宽 | `calendarContentWidth / 7` |
| 月格高度 | 按 5/6 周与内容密度动态分配，不从录屏硬抄 px |
| Sticker 视觉主体 | 约 `0.40–0.45 × dateCellWidth` |
| Sticker 交互圈 | 约 `1.00–1.05 × dateCellWidth` |
| Sticker 日期锚点 | 交互圈中心与 Date Cell 做 hit-test |
| Sticker 位置 | 视觉自由放置；不要强吸附到日期格中心 |

---

## 4. 圆角系统：少、低、分层

### 4.1 实机测得的圆角层级

| 组件 | B 级观察 | 建议 Token |
|---|---|---:|
| 页面、表单行、列表行 | 直角，靠分隔与留白组织 | `0dp` |
| 标准 Dialog | 顶角约 6 原图 px，折算约 2dp | `2dp` |
| 全宽视图切换 Bottom Sheet | 本批画面为直角顶边 | `0dp`（保真实机） |
| Anchored menu | 轻微圆角和阴影 | `4–6dp` |
| App Bar Save | 小矩形，不是胶囊 | `4–6dp` |
| 搜索框/轻输入底 | 轻圆角 | `4–8dp` |
| Filter chip / 紧凑筛选 | 高度的一半，胶囊只用于短标签 | `999dp` |
| 完成圆、Star、中央 `+` | 几何圆形 | `50%` |
| 选中日 Agenda 附着面板 | 可有较大的顶部圆角与 handle | 以当前组件实证另行冻结 |

### 4.2 对旧规格的纠正

旧 Popup 母档曾给出 `Bottom Sheet radius = 16–20dp`。本批 B 级截图中的 Calendar 视图切换 Sheet 为全宽直角面板，因此：

- `16–20dp` 只能保留为 Looka 的 clean-room 设计选项；
- 不得继续称为 Lifebear 实机定值；
- 若目标是高保真 Lifebear，视图切换 Sheet 应优先使用直角；
- 若 Looka 主动改用圆角，必须在后续对齐文档中记录为“品牌化偏离”。

### 4.3 圆角的统一原则

Lifebear 不是“圆角越小越高级”，而是只有需要表达浮层、筛选或可触摸几何时才使用圆角。圆角不承担页面分组职责，分组主要由标题、间距和分隔线完成。

---

## 5. Dialog、Popup 与 Bottom Sheet 数据

### 5.1 典型实机测量

| 类型 | 证据 | 原图面板 | 屏幕占比 | 约 dp | 主要结论 |
|---|---|---:|---:|---:|---|
| Single-choice Dialog | 月 Calendar 滚动形式 | `977 × 491px` | `86.0%W × 18.3%H` | `326 × 164dp` | 内容驱动高度；两项单选；轻小圆角 |
| Delete Confirm | 删除 Event | `977 × 492px` | `86.0%W × 18.3%H` | `326 × 164dp` | 标题、说明、Cancel/Delete；不额外放图标 |
| Visual-choice Dialog | 日期选中后的显示形式 | `977 × 1207px` | `86.0%W × 44.9%H` | `326 × 402dp` | 需要看预览时允许更高面板 |
| Range-choice Dialog | 完成任务期间选择 | 约 `977 × 537px` | `86.0%W × 20.0%H` | `326 × 179dp` | 行高稳定；当前项用灰底 |
| Compact Create List | 键盘可见时创建 List | `977 × 739px` | `86.0%W × 27.5%H` | `326 × 246dp` | 位于键盘上方；标题、输入、两动作 |
| Expanded Color List | 键盘可见且色盘展开 | `977 × 1540px` | `86.0%W × 57.2%H` | `326 × 513dp` | 利用键盘上方可用区；内容滚动、动作区稳定 |
| View-switch Bottom Sheet | Month/Week/Day/日期移动/显示设置 | `1136 × 1052px` | `100%W × 40.7%H` | 高度随内容 | 全宽直角；用户主动打开；保留背景上下文 |
| Anchored menu | Task/Note 的 Edit/More | 约 `54%W × 7–10%H` | 内容驱动 | — | 锚定触发点；无全屏转场 |
| Sticker context popover | 予定登録/删除 | 约 `42–45%W` | 极短 | — | 与 Sticker 锚点对齐；有指向箭头 |

说明：dp 约值只用于量级判断。面板宽度、屏幕占比和相对位置比绝对 dp 更可靠。

### 5.2 标准 Dialog 的视觉数据

- 左右屏幕边距：原图约 `79px`，折算约 `26dp`。
- 面板宽度：约屏幕的 `86%`。
- 内部左右边距：约 `24–26dp`。
- 圆角：约 `2dp`。
- Scrim：对白底区域采样约为 `RGB(102,102,102)`，等效接近 `black 60%`；JPEG 误差约 ±2。
- 表面：纯白。
- 阴影：存在但克制，主要用于与强 Scrim 分层，不形成漂浮卡片感。
- 标题：左对齐；动作在右下，通常为文字按钮。
- 标准确认框不使用“大图标 + 大标题 + 大按钮”的营销卡片结构。

### 5.3 高度规则

Dialog 高度不能用一个全局固定值。应按以下顺序决定：

1. 先按内容自然高度布局。
2. 两到三项简单选择维持短 Dialog，约屏幕高的 18–20%。
3. 需要视觉比较时可以到约 45% 屏幕高。
4. 有键盘时，面板以“键盘上方可用高度”为坐标系，不强行对整屏垂直居中。
5. 色盘、长列表等内容允许 Dialog 内滚动，标题和底部动作要稳定。
6. 超过一个对象编辑任务时，优先升级为 Full-page Editor，不继续拉高 Dialog。

### 5.4 动作布局

| 场景 | Lifebear 语法 |
|---|---|
| 取消/确认 | 右下文字动作；Cancel 在左，确认/保存/删除在右 |
| Single choice | 点选即可表达完整意图时，可立即提交并关闭 |
| Multi choice | 复选只修改 Dialog draft；需要 Cancel + OK/Done |
| Delete | 必须二次确认；危险动词直接写出对象语义 |
| 保存成功 | 返回原上下文并更新内容；不再弹成功 Dialog |
| Capability gate | Close 回原位；Learn More 才离开当前上下文 |

### 5.5 Bottom Sheet 的使用边界

适合：

- Month/Week/Day 等短导航；
- 日期移动、显示设置入口；
- 短预览与短个性化选择。

不适合：

- 多字段 Event/Task 编辑；
- 可滚动的对象管理；
- 需要明确 Save/Cancel 的长事务；
- 删除或重复系列范围等高风险决策。

---

## 6. 字体层级与信息密度

### 6.1 可冻结的语义级别

截图无法证明精确系统字体，因此下表是 B-测范围与 C 级实现建议：

| Role | 画面量级 | 建议 sp | 字重 | 用途 |
|---|---:|---:|---|---|
| Month display | 大号、短文本 | `28–32sp` | Bold | 年/月主标题 |
| Dialog title | 明显高于正文 | `20sp` | Medium/SemiBold | 删除、选择、创建 Dialog 标题 |
| Page title | 稳定但不夸张 | `18sp` | SemiBold | App Bar 页面标题 |
| Empty-state title | 中等强调 | `18sp` | SemiBold | “任务为 0 件”等空状态 |
| Primary body | 主阅读层 | `16sp` | Regular | Dialog 正文、主要表单值 |
| Row primary | 稍紧凑 | `14–16sp` | Regular/Medium | 设置项、任务标题、List 名 |
| Action label | 紧凑明确 | `14–16sp` | Save、Cancel、Delete、Edit |
| Secondary | 弱层级 | `12sp` | Regular | 摘要、日期、来源、辅助说明 |
| Calendar weekday/date | 高密度 | `10–12sp` | Regular | 星期、日号 |
| Calendar item | 极高密度 | `8–10sp` | Regular/Medium | 月格 Event、任务、节日 |
| Bottom-nav label | 小但持续可读 | `10–11sp` | Regular/Medium | 一级导航标签 |

### 6.2 排版原则

- 字体使用系统 UI Sans；中文实现优先系统中文无衬线，不额外引入强品牌字体。
- 大多数正文不加粗；加粗主要给页面标题、Dialog 标题、空状态和关键日期。
- 次级文字可以变灰，但不能小到依赖猜测。
- Calendar 的小字号成立，是因为有严格的 7 列栅格和位置语义；不能把同样的小字号复制到普通表单。
- 标题、正文、动作最多使用 3 个同时可见的视觉层级，避免一屏出现 5 种字号。
- 日本原版字符宽度与中文不同，中文标题和按钮必须按真实文案重新验收，不能直接复制日文宽度。

---

## 7. 颜色、线条与状态

### 7.1 全局颜色角色

| Role | Lifebear 观察 | 约束 |
|---|---|---|
| Surface | 白色 | 长期主背景 |
| Primary text | 近黑 | 页面标题、正文、关键数值 |
| Secondary text | 中灰 | 摘要、来源、辅助说明 |
| Divider | 极浅灰 | 只做结构，不形成卡片框 |
| Selection | 黑实心/黑线/灰底/check | 不只依赖主题色 |
| Sunday / holiday | 红 | Calendar 时间语义 |
| Saturday | 蓝 | Calendar 时间语义 |
| Category / Source | 色点、色块 | 分类与来源语义 |
| Star | 橙黄 | Task 星标状态 |
| Scrim | 黑约 60% | 阻断 Dialog/Sheet 的焦点分层 |

### 7.2 颜色使用纪律

- 品牌色不是全局“按钮颜色”。
- Dialog 的确认动作通常仍是近黑文字，不因主题改变危险语义。
- Event/Calendar 的颜色用于识别内容，不用来涂满设置页。
- 主题可以替换 accent、weekend、event 等语义 Token，但不能改变触达尺寸、选择可识别性和危险动作层级。
- 任何状态都要同时有形状、位置、文字或图标证据，不能只靠颜色。

---

## 8. 组件选择矩阵

### 8.1 Settings Preference Router

| 数据/决策类型 | 正确容器 | 提交规则 |
|---|---|---|
| 稳定、低风险、可逆布尔值 | Inline Toggle | 点击立即提交；失败回滚并非阻断提示 |
| 单一枚举 | Single-choice Dialog | 点选即可提交并关闭 |
| 多个独立选项的集合 | Multi-choice Dialog | Dialog 内 draft；OK/Done 一次提交 |
| 需要看效果再选 | Visual-choice Dialog | 提供预览；比较完成后提交 |
| 对象型配置或多层导航 | Full-page Editor/List | Back/X 返回；按对象合同保存 |
| 受 Plan 限制的明确能力 | Capability Gate | 仅用户直接点该能力时出现 |
| 有输入、会创建独立实体 | Draft Editor | Save 是事务边界；dirty 退出需确认 |
| 不可逆删除 | Destructive Confirm | 明确对象、后果和 Cancel/Delete |

### 8.2 组件不应互换

- Toggle 不能代替枚举选择。
- Single choice 不应多出无意义的“确定”。
- Multi choice 不能边勾边悄悄保存。
- Full-page 页面不进入 Overlay 队列。
- More menu 只负责选择动作，Delete 仍要进入 blocking Dialog。
- Field Date Picker 只提交字段值，不等于保存整个 Event。

---

## 9. Motion、Pressed 与响应预算

### 9.1 B 级行为特征

- 触摸后先出现整行或控件 pressed，再进入页面或 Dialog。
- Dialog 快速出现，没有明显 spring、overshoot 或装饰性缩放。
- 单选/Toggle 立即给状态反馈。
- Full-page push/pop 保留方向性，让用户知道从父页进入子页。
- 键盘与 Dialog 可以同屏，但文本焦点只有一个所有者。
- 用户滚动、拖动、输入时，AI/运营层不抢占。

### 9.2 C 级统一时序

| 动作 | 建议时长 | 规则 |
|---|---:|---|
| Pressed row | `60–100ms` | 只变浅灰/透明度，不位移 |
| Tap recognized | `60–100ms` | 锁定目标，避免和父滚动抢解释 |
| Dialog first frame | `100–180ms` | Scrim 与 surface 同步；不等网络 |
| Dialog close | `120–220ms` | 恢复原 scroll/focus/selection |
| Full-page push | `220–300ms` | 平台原生 ease |
| Full-page pop | `180–260ms` | 略快于 push |
| Date selected | `140–180ms` | 先有选择反馈，再换详情 |
| Detail swap | `160–220ms` | Fade + `4–8dp` 轻位移 |
| Bottom Sheet in | `260–300ms` | Scrim `160–200ms` 同步 |
| Snackbar in | `160–200ms` | 停留 `1.8–3s`；不抢焦点 |

响应目标：

- 触摸到首帧：P95 小于 `100ms`。
- 本地状态更新：不超过 `200ms`。
- 首个可用内容：不超过 `500ms`。
- 网络超过 `800ms` 才出现“正在同步”语义，避免闪烁 loading。
- 重任务超过 `2s` 时提供进度、取消或后台路径。

---

## 10. Overlay 层级与焦点所有权

### 10.1 层级表

| 层 | 组件 | 典型场景 | 是否阻断 |
|---|---|---|---|
| N | Full-page push | Calendar/Notification/Recurrence/Editor | 不是 Overlay |
| L0 | Inline / Expander | 冲突提示、Recurrence calendar | 否 |
| L1 | Toast / Snackbar | 保存、撤销、复制 | 否 |
| L2 | Banner | 离线、同步失败 | 否 |
| L3 | Field Picker / Bottom Sheet | 日期、视图切换、短预览 | 弱阻断 |
| L4 | Dialog / Action Sheet | 删除、范围、解绑、创建 List | 是 |
| L5 | System Permission | 通知、相册、日历权限 | 系统阻断 |

### 10.2 硬规则

1. 同一时刻最多一个 interactive Overlay。
2. Full-page 不进入 Overlay 队列。
3. Top modal 是唯一 focus owner。
4. IME 只能绑定 parent editor 或 top modal 之一。
5. 打开用户主动层时，取消所有低优先级 AI/运营 pending Overlay。
6. 关闭阻断层后，恢复原页面的 scroll anchor、selection、selectedDate 和 draft。
7. Dialog 与系统键盘动画不能串成两次完整等待。
8. 保存成功使用 Snackbar/inline，不使用 success Dialog。

### 10.3 系统主动层的频控

- 页面稳定 `0–600ms` 内禁止主动 AI/运营浮层。
- 高价值 AI 建议最早在页面稳定且 idle `≥900ms` 后出现。
- 同一页面单次访问最多一个非必要主动浮层。
- 同类 AI 建议关闭后，本会话不再出现，默认冷却 24 小时。
- 用户开始 scroll/drag/type 时暂停队列并重新计时，不在结束后“补弹”。

---

## 11. 功能键与交互链路

### 11.1 Global Shell

| 入口 | 角色 |
|---|---|
| Calendar | 时间聚合层 |
| ToDo | 完成导向的任务系统 |
| 中央 `+` | 根据当前模块创建对应对象 |
| Note & Diary | 同一一级入口中的两种记录心智 |
| More | 账号、Plan、Settings、Shop、帮助与维护 |

底部中央 `+` 是唯一持续的强动作锚点。它不能被广告、Sheet 或悬浮 AI 入口遮挡。

### 11.2 Calendar：查看与切换

```text
Calendar
  ├─ 点日期 → 首帧 Selected → 保留月历 → 更新下方 Agenda/Diary
  ├─ 点视图入口 → View-switch Bottom Sheet
  │    ├─ Month / Week / Day → 选择后关闭并保持时间上下文
  │    ├─ 日期移动 → 进入日期选择
  │    └─ 显示设置 → 进入设置层
  └─ 中央 + → 根据当前上下文建立 Event/Task 等对象
```

关键体验：进入某一天不丢失 Calendar 上下文；Month 是总览与选择层，Agenda 是选中日期的内容层。

### 11.3 Event：父编辑器与子编辑器

```text
Calendar 上下文
  → Event Create（标题立即聚焦）
  → 父页显示 Date / Calendar / Notification / Recurrence 摘要
  → 点复杂字段进入专用 Selector 或 Editor
  → 子页更新 Event draft，返回父页立即看到摘要变化
  → 父页 Save 才成为最终事务边界
  → 返回原 Calendar 日期，立即显示结果
```

功能键语义：

| 控件 | 语义 |
|---|---|
| `X` | 取消/放弃当前创建；dirty 时进入 Discard Dialog |
| Back arrow | 返回父上下文，默认保留父 draft |
| Black Save | 提交对象；点击后防重复提交 |
| Checkmark | 当前选择或 enabled，不等于 Save |
| Picker Done | 提交临时字段值，只关闭 Picker |
| “显示详细” | 渐进披露低频字段，不打开第二张万能表单 |

### 11.4 Task：高频状态与低频动作分离

```text
ToDo List
  → 快速添加 / 打开 Task Detail
  → Completion、Star 在详情本体即时切换
  → Edit 直达
  → More menu
       ├─ Copy → 预填的新 Task draft → Save 后创建新对象
       └─ Delete → Delete Dialog → 删除原生 Task → Calendar 投影刷新
```

规则：Copy 不是立即复制；它是从原 Task 生成一个新的可取消 draft。

### 11.5 Note / List：持久容器与键盘所有权

```text
Note 列表
  → Note Create（List + 标题 + 正文）
  → 点 List 字段
  → List Change Dialog
       ├─ 选择已有 List → 更新 Note draft → 关闭
       └─ Create List → 替换为 Create List Dialog + IME
                            → Save List → 持久化 + 自动选择
  → 回到 Note Editor，标题/正文/光标不丢
  → Save Note → 回当前 List → 新内容立即出现
```

核心不是“键盘一定隐藏或显示”，而是：

- List 是持久对象，不是临时字符串；
- Parent Note draft 不丢；
- 同时只有一个 top modal；
- 同时只有一个 active text client；
- 保存后 local-first 投影到当前 List。

### 11.6 Sticker：自由感与结构化同时存在

```text
Sticker Tray
  → 选择/拖动素材
  → 固定 Interaction Frame 跟手
  → 松手后保留自由位置，同时由中心命中 host_date
  → 点 Decorative Sticker
       ├─ 予定登録 → 以 host_date 打开 Event Create
       └─ 删除 → 只删除 StickerInstance
  → Event Save
  → Calendar 显示 Event-bound Sticker
  → 再点时菜单语义变为 Edit/Delete
```

禁止把 Sticker 简化成“Event 上的一张图片”。Asset、Instance、Date anchor 与 EventRef 必须分离。

### 11.7 Settings：Pressed 先于 Dialog

```text
Pointer down 0–60ms
  → 整行 pressed
  → Tap recognized 60–100ms
  → 根据数据类型选择 Toggle / Dialog / Full-page / Gate
  → 选择反馈 <100ms
  → Close 120–220ms
  → 回到相同 scroll anchor 与触发行
```

---

## 12. 空、加载、错误、成功与删除反馈

| 状态 | Lifebear 方向 | 实现规则 |
|---|---|---|
| Empty | 熊插画 + 明确创建引导 | 情绪温暖，但不盖住中央 `+` |
| Local cache | 立即显示 | 后台静默同步 |
| <250ms request | 不显示 loading | 避免 spinner 闪烁 |
| 250–800ms | 局部 skeleton | 不挡日期和表单导航 |
| >800ms | skeleton + 正在同步 | 允许继续浏览；旧请求可取消 |
| Offline | 顶部 Banner | 本地编辑继续；恢复后自动同步 |
| Sync failure | Banner / item error | 可重试；不全屏打断 |
| Save success | 原位更新 / Snackbar | 不弹成功 Dialog |
| Delete | 先确认；删除后可用 Snackbar Undo | Undo 不得被底栏、广告或键盘遮住 |

---

## 13. 全局一致性验收表

### 13.1 视觉

- [ ] 页面以白底、留白和分隔组织，没有无意义卡片化。
- [ ] 标准 Dialog 宽约屏幕 86%，内部水平边距约 24dp。
- [ ] 标准 Dialog 使用约 2dp 小圆角，不套用全局大圆角。
- [ ] View-switch Bottom Sheet 若追随本批实机，使用全宽直角。
- [ ] App Bar Save 为小矩形深色按钮，不是大胶囊。
- [ ] 强色只用于状态、分类、周末或个性化语义。
- [ ] 同屏字号层级不超过三层，Calendar 高密度区除外。

### 13.2 交互

- [ ] 所有行点击先有 pressed，再导航/弹层。
- [ ] Single choice 可直接 select-and-close。
- [ ] Multi choice 有独立 commit。
- [ ] Full-page Editor 的 Save/X/Back 语义一致。
- [ ] Picker Done 不被误当作整个对象 Save。
- [ ] More menu 选择 Delete 后仍进入阻断确认。
- [ ] Copy 进入新 draft，不立即生成对象。
- [ ] 任何关闭动作都不丢 parent draft、selection 和 scroll anchor。

### 13.3 Overlay 与 IME

- [ ] 同时最多一个 interactive Overlay。
- [ ] Top modal 是唯一 focus owner。
- [ ] 键盘与 Dialog 同屏时只有一个 active text client。
- [ ] Dialog 关闭后恢复原控件焦点或明确的新目标。
- [ ] AI/运营提示不会在滚动、输入、拖动和删除确认期间出现。
- [ ] Snackbar 不挡底栏、广告、键盘与下一步 CTA。

### 13.4 功能链路

- [ ] Calendar → Date → Agenda 保留上下文。
- [ ] Event 子编辑器返回后父页摘要立即更新。
- [ ] Task Completion/Star 即时，Copy/Delete 事务边界清楚。
- [ ] Note 切换/创建 List 时正文和标题不丢。
- [ ] Sticker host_date 来自落点，视觉位置不被强吸附。
- [ ] 保存后 local-first 回到正确列表或日期，并立即看到结果。

---

## 14. 仍未被实机完全证明的项目

以下项目不得写成 Lifebear 已冻结事实：

1. 原生字体的精确名称、字重和 sp。
2. Dialog shadow 的完整 elevation、blur 和 alpha。
3. 所有设备、系统版本和横屏下的 Dialog 宽度策略。
4. View-switch Bottom Sheet 是否在其他平台/版本使用圆角。
5. Recurrence Back 到底是自动应用 draft，还是 dirty 时询问放弃。
6. Event-bound Sticker 删除时是否同时删除 Event。
7. 所有 Motion 的原生曲线与逐毫秒常量。
8. TalkBack/VoiceOver 的实际焦点顺序和语义标签。
9. 系统字体放大后，Calendar 与长 Dialog 的重排规则。
10. 深色模式；本批证据只支持浅色 Calm Utility。

---

## 15. 证据索引

### 15.1 核心母档

- `looka所有文档/Lifebear_Product_DNA_UX_Philosophy_Interaction_System_v1.0.docx`
- `looka所有文档/Lifebear_Calendar_UIUX_开发规格_v1.1.docx`
- `looka所有文档/Calendar_UIUX_Design_Interaction_Popup_Spec_v1.3_V012_V013_V014_L001.docx`
- `looka所有文档/Lifebear_Sticker_Canvas_Asset_Standard_v1.0.docx`
- `looka所有文档/Lifebear_Sticker_Module_UIUX_Interaction_Motion_Implementation_Spec_v1.0.docx`
- `docs/NOTES-DIARY-LIFEBEAR-AUDIT.md`
- `docs/TASK-LIST-LIFECYCLE-AUDIT.md`

### 15.2 关键 B 级截图

| 证据 | 证明内容 |
|---|---|
| `参考组件图标/0821/微信图片_20260820133740_14_18.jpg` | View-switch Bottom Sheet 的全宽、直角、约 40.7% 高度与强 Scrim |
| `参考组件图标/0822/微信图片_20260822203452_31_4.jpg` | Single-choice Dialog 的宽度、高度、圆角、Radio 和动作布局 |
| `参考组件图标/0822/微信图片_20260822203453_32_4.jpg` | Visual-choice Dialog 的预览结构与较高面板 |
| `参考组件图标/0833/微信图片_20260823182237_52_13.jpg` | Delete Confirm 的标准短 Dialog |
| `参考组件图标/0833/微信图片_20260824171100_48_4.jpg` | Range-choice Dialog 的列表选择和选中灰底 |
| `参考组件图标/0833/微信图片_20260824171103_52_4.jpg` | 键盘可见时的 Expanded Color List Dialog |
| `参考组件图标/0833/微信图片_20260824171107_57_4.jpg` | Compact Create List Dialog |
| `参考组件图标/0833/微信图片_20260823181526_50_13.jpg` | Sticker context popover、Picker 与 Calendar 同屏结构 |

### 15.3 关键 B 级录屏

| 证据 | 本次复核范围 |
|---|---|
| `参考组件图标/0822/7f9d7827f156eb3d1e355221127db65e.mp4` | More → Settings、选择 Dialog、Toggle、Template、Calendar 管理 |
| `参考组件图标/0833/0dffdedc81056c150141ff4a1153815e.mp4` | Note Create、List Change/Create、IME、保存回 List |
| `参考组件图标/0826/df9a1e8a886ad6b560e2fbd23e83dc94.mp4` | Task/List 重命名、快速添加、详情、排序与空状态 |
| `参考组件图标/0833/ac3e6f1d560d921bf7b0ba0b4cf031b9.mp4` | Calendar/Event 创建、日期上下文、Sticker 与 Event 投影 |

---

## 16. 下一阶段如何使用本文

本文完成的是“先研究 Lifebear，建立统一坐标系”。下一阶段如果进入 Looka 全局对比，应另外建立差距矩阵：

```text
Lifebear B 级事实
  + 本文 C 级统一 Token
  ↓
Looka Android 当前 Build [L]
  + Looka Web 当前 Build [L]
  ↓
逐组件比较：视觉 / 状态 / 交互 / 数据事务 / 双端一致性
  ↓
只提出修复计划，不把差距直接当成已授权改动
```

届时每一条差距都应回答五个问题：

1. Lifebear 证据是什么？
2. Looka 当前真实行为是什么？
3. 差异是缺陷、主动品牌化偏离，还是平台合理差异？
4. 修复会影响 Android、Web、数据层或无障碍中的哪些部分？
5. 真机如何验收，什么情况下才能从 `[~]` 变为 `[x]`？
