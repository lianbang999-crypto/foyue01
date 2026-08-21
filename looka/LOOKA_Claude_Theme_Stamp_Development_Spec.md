# LOOKA GPT Function Calling + Theme Stamp Skill 开发规范

## 给 Claude 的执行说明

你现在作为 LOOKA 项目的 AI 工程开发者。

请根据本文档实现代码。

目标： 完成 LOOKA Theme Stamp 模块接入：

User → LOOKA Chat → GPT → Theme Stamp Skill → Asset Library → Sticker
Picker → Calendar

------------------------------------------------------------------------

## 核心原则

LOOKA 不依赖 AI 单次生图。

核心资产体系：

AI 生成能力 + Theme Stamp Asset System + Calendar Application

------------------------------------------------------------------------

## GPT Function Calling

Tool Name:

`generate_theme_stamp`

用途：

当用户需要创建主题印章时调用。

输入：

-   theme_name
-   description
-   style
-   count（默认24，最大40）
-   category
-   character

------------------------------------------------------------------------

## Skill 作用

该 Skill 不负责图片生成。

图片由 GPT Image Generation 完成。

Skill 负责：

1.  接收生成图片
2.  去背景
3.  裁剪主体
4.  统一尺寸
5.  转换 WebP
6.  生成 metadata
7.  创建 Theme Pack
8.  写入 Asset Library

------------------------------------------------------------------------

## Asset 标准

固定：

-   Size: 256×256
-   Format: WebP
-   Background: Transparent

------------------------------------------------------------------------

## 数据模型

### stamp_assets

包含：

-   asset_id
-   theme_id
-   name
-   image_url
-   category
-   tags
-   style
-   width
-   height
-   format
-   created_at

### stamp_themes

包含：

-   theme_id
-   name
-   description
-   cover_image
-   asset_count
-   style
-   created_at

### calendar_stamp_instances

包含：

-   calendar_event_id
-   asset_id
-   position_x
-   position_y
-   scale
-   rotation

------------------------------------------------------------------------

## API

创建主题：

POST

/api/ai/theme-stamp/generate

查询任务：

GET

/api/ai/theme-stamp/jobs/{job_id}

资产：

POST

/api/assets/stamps

主题：

GET

/api/themes/{theme_id}

------------------------------------------------------------------------

## 开发阶段

Phase 1: 完成 Asset Library 和 Theme API

Phase 2: 完成 Sticker Picker

Phase 3: 完成 Calendar Stamp 应用

Phase 4: 完成 GPT Function Calling

Phase 5: 完成 Image Pipeline

------------------------------------------------------------------------

## Claude 执行要求

请不要只输出设计文档。

请直接：

1.  创建项目结构
2.  创建数据库模型
3.  创建 API
4.  创建 Function Calling Schema
5.  创建 Skill Handler
6.  创建前端组件
7.  输出运行说明

目标：

用户输入：

"帮我生成一个狗狗生日主题手帐"

LOOKA 自动完成：

GPT理解 → 生成图片 → Theme Stamp Skill处理 → Asset Library入库 →
用户应用到日历。
