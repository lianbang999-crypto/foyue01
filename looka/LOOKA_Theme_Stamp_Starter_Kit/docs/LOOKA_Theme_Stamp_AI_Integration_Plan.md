# LOOKA Theme Stamp AI 印章系统接入规划

## 1. 目标
为 LOOKA 日历/手帐 App 建立一套可扩展的 AI 印章系统，让用户通过聊天即可生成主题贴纸包，并一键应用到日历、手帐和 Sticker Picker。

## 2. 第一阶段范围
第一阶段先完成官方 Demo 包（牛牛主题）接入，跑通完整闭环：
1. 资产入库
2. 主题包读取
3. Sticker Picker 展示
4. 日历应用
5. AI Skill 接口预留

## 3. 统一资产规范
- 尺寸：256×256 px
- 格式：WebP
- 背景：透明
- 风格：LOOKA Cute Lifestyle Sticker
- 单主题包默认数量：24
- 单次生成上限：40

## 4. 业务模块
### 4.1 Asset Library
- 管理所有主题包与单个印章
- 支持按主题、分类、标签检索
- 支持版本控制与上下线

### 4.2 Sticker Picker
- 展示主题包封面与明细
- 支持搜索、筛选、最近使用
- 支持拖拽或点击插入日历

### 4.3 Theme Stamp Skill
- 接收用户自然语言
- 分析主题与数量
- 生成印章蓝图
- 输出资产与 metadata
- 回传给 LOOKA 审核/入库

### 4.4 Calendar / Journal 应用
- 将印章附着到某日、某时段或某手帐页面
- 保存坐标、缩放、旋转、图层顺序

## 5. 推荐实现顺序
1. 先做本地静态主题包读取
2. 接入主题 manifest（theme.json）
3. 接入 asset metadata（metadata.json）
4. 完成 Sticker Picker UI
5. 完成日历插入逻辑
6. 最后接入 AI 生成 Job 流程

## 6. AI 生成闭环
用户输入主题需求 → LOOKA AI → Theme Stamp Skill → 生成资产 → 人工/自动质检 → 发布入库 → 用户选择并应用。

## 7. 第一阶段交付定义
- 牛牛主题包能在 App 中完整展示
- 每枚印章可正常插入日历/手帐
- Asset Library 可以通过 theme_id + asset_id 查找资源
- Skill 接口 contract 已就绪，方便下一阶段由 Claude 接入后端逻辑
