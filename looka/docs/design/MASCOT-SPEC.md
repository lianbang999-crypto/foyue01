# 小鹿形象设计规格 v1.0(§125 · 供生成/委托设计用)

> 用途:重新设计 Looka 的小鹿 mascot。本文是**生成任务书**——拿着它去 AI 生图或委托设计,
> 产出按 §4 验收。原则承接 looka-next 拍板:**Mascot 拆 Identity / Skin** ——
> 造型(Identity)永远不变,配色(Skin)随主题可换。

## 1. 气质定位(先对齐,再动笔)

对标 Lifebear 蓝熊的**功能位**(空状态引导、品牌识别),但气质是我们自己的:

- **九色鹿意象**:敦煌《鹿王本生》的白鹿——通体暖白,身上九个彩色斑点(这是"一鹿九色"主题体系的具象来源)
- **可爱但克制**:圆润简笔、大头短身、两点眼 + 小弧线嘴;**不要**大眼闪光、腮红过重、日系萌化过度
- **安静**:默认姿态是"陪着你",不是"看着你"——侧身、低头、卧姿优于正面瞪视
- 一句话验收:放在白底细线的日历页角落,它应该像手帐上的一枚印章,而不是跳出来的吉祥物

## 2. 造型规格(Identity,冻结项)

| 项 | 规格 |
|---|---|
| 比例 | 头:身 ≈ 1:1.2(Q 版但不是大头贴);站高:宽 ≈ 1:0.8 |
| 轮廓 | 全圆角,无尖角;鹿角小而圆润(两叉幼角,不是成年鹿大角) |
| 眼睛 | 两个实心圆点(不画高光点也成立);间距宽一点显温和 |
| 斑点 | 背部/臀部 3-5 个可见圆斑(完整九斑只在全身大图出现) |
| 线条 | 两种版本:①无描边纯色块(主用) ②1.5-2px 细描边版(与 LkIcons 图标家族同语言,用于小尺寸) |
| 尾巴 | 短圆点尾 |

## 3. 配色规格(Skin,可变项)

| 版本 | 用途 | 规则 |
|---|---|---|
| **单色可染版**(必须) | DeerBadge 头像、导航图标、加载态 | 纯剪影/单色线稿,SVG 单 path 或可 tint 的 PNG——App 会用主题 accent 现染(现有 DeerBadge 随主题变色的机制要继续成立) |
| **暖白全彩版**(必须) | 空态插画、锁屏、商店封面 | 主体暖白 #F5F1E8 左右,斑点用九色(低饱和处理),鼻/蹄深暖灰;背景透明 |
| 主题联动版(可选) | 皮肤包内 | 斑点色随包主色 |

## 4. 产出清单(按此验收)

| # | 资产 | 尺寸 | 格式 | 姿态/表情 |
|---|---|---|---|---|
| M1 | 头像母版 | 1024×1024 | SVG 首选,否则 PNG 透明底 | 头部特写,平静微笑 |
| M2 | 单色剪影版 | 同上 | SVG(单色可染) | 同 M1 轮廓 |
| M3 | 全身站姿 | 1024×1024 | PNG 透明底 | 站,回头看,九斑完整 |
| M4 | 空态·想事情 | 1024×1024 | PNG 透明底 | 坐,歪头(空清单/无结果用) |
| M5 | 空态·睡觉 | 1024×1024 | PNG 透明底 | 卧,闭眼(夜间/无提醒用) |
| M6 | 庆祝 | 1024×1024 | PNG 透明底 | 小跳,不夸张(完成态用,克制) |
| M7 | 看图/识别 | 1024×1024 | PNG 透明底 | 低头看一张小纸(AI 识图中态) |
| M8 | 写字/记录 | 1024×1024 | PNG 透明底 | 叼笔或蹄按本子(AI 记录场景) |
| M9 | 启动图 | 1024×1024 居中主体 ≤60% | PNG | M1 或 M3 |

技术要求:主体四周留 ≥10% 透明出血(圆形裁切安全区);同批次风格必须一致
(建议一次生成会话产完整套,或先产 M1 定风格再据它做变体)。

## 5. 生成提示词骨架(可直接用)

> A minimal flat-design mascot: a chubby little white deer from Dunhuang
> "Nine-Colored Deer" legend, cream-white body with small colorful round spots
> on its back, tiny rounded antlers, two simple dot eyes, gentle closed-mouth smile,
> soft rounded shapes, no outline (或: with thin 2px outline), pastel and restrained,
> sitting quietly / looking back / sleeping…(按 M3-M8 换姿态),
> flat illustration, transparent background, no gradient, no glow, no blush overload,
> calm Japanese stationery aesthetic (like Lifebear's bear: functional, not loud)

## 6. 验收清单

- [ ] 缩到 20dp(底栏尺寸)仍能认出是鹿
- [ ] 单色版染成九色任意一色都成立
- [ ] 放进白底日历页不抢内容(§1 印章测试)
- [ ] 九套主题下斑点色不与语义色(周末红蓝/删除红)混淆
- [ ] 全套 M1-M9 并排看是同一只鹿
