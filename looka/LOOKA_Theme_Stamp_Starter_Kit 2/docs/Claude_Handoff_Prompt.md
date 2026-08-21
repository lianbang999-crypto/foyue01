# Claude 接手提示词（可直接复制）

你现在要作为 LOOKA 项目的开发协作者，基于我提供的 `LOOKA_Theme_Stamp_Starter_Kit` 完成 Theme Stamp 模块接入。请按以下要求执行：

## 目标
完成 LOOKA Theme Stamp 系统的第一阶段接入，让官方 Demo 包“牛牛主题”可以在 App 中被读取、展示、选择并应用到日历/手帐中，同时预留 AI Skill 接入能力。

## 你将收到的材料
1. `assets/stamps/`：24 枚 256×256 WebP 透明背景贴纸
2. `data/theme.json`：主题包 manifest
3. `data/metadata.json`：每枚贴纸的元数据
4. `data/database_schema.json`：数据库结构建议
5. `skill/skill_contract.json`：Theme Stamp Skill 接口约定
6. `docs/`：产品规划、Skill 规范、开发任务拆分

## 交付要求
请按以下顺序输出：
1. 技术实现方案
2. 前端数据结构与状态管理设计
3. 后端 API 设计与数据模型
4. Sticker Picker UI 组件结构
5. 日历/手帐插入贴纸的交互流程
6. AI Theme Stamp Skill 接入流程
7. 分阶段开发计划（M1~M4）
8. 如果可行，请直接给出关键代码骨架或伪代码

## 约束
- 统一资产规格：256×256 WebP
- 256 为源资产，客户端按目标尺寸自动降采样（不再需要多套尺寸）
- 主题包默认数量：24
- 单次生成上限：40
- App 端先完成静态主题包读取，再接入 AI 生成功能
- 生成任务采用异步 Job 机制

## 优先级
P0：读取牛牛主题包并在 Sticker Picker 中显示
P1：贴纸可插入日历/手帐
P2：Asset Library / Theme API
P3：AI Skill 生成与发布流程

请先输出整体实现方案，然后分模块展开。