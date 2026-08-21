# LOOKA Theme Stamp Integration Skill

## Skill Name

looka_theme_stamp_integration

## Purpose

将 GPT 生成的图片接入 LOOKA Theme Stamp 主题印章系统。

该 Skill 不负责图片生成。

图片生成由 GPT Image Generation 完成。

本 Skill 负责：

-   接收 GPT 图片结果
-   标准化图片资产
-   生成印章 Metadata
-   创建 Theme Pack
-   写入 Asset Library
-   提供 LOOKA App 调用

------------------------------------------------------------------------

# System Flow

User

↓

LOOKA Chat

↓

GPT

↓

Image Generation

↓

Theme Stamp Skill

↓

Asset Library

↓

Sticker Picker

↓

Calendar / Journal

------------------------------------------------------------------------

# Input Schema

``` json
{
  "image_url": "",
  "user_prompt": "",
  "theme_name": "",
  "generation_id": "",
  "style": "LOOKA Cute Sticker",
  "count": 24
}
```

------------------------------------------------------------------------

# Processing Pipeline

## Step 1: Receive Image

获取 GPT Image Generation 输出。

## Step 2: Normalize Asset

处理：

-   Remove Background
-   Crop Subject
-   Center Alignment
-   Resize

固定输出：

    256x256 WebP
    Transparent Background

## Step 3: Quality Check

检查：

-   尺寸是否正确
-   是否透明背景
-   是否包含文字
-   是否包含水印
-   是否符合 LOOKA 风格

------------------------------------------------------------------------

# Metadata Generation

输出：

metadata.json

Example:

``` json
{
 "asset_id":"looka_stamp_001",
 "theme_id":"summer_trip_001",
 "name":"Travel Bear",
 "category":"life",
 "tags":[
   "travel",
   "cute"
 ],
 "format":"webp",
 "size":{
   "width":128,
   "height":128
 }
}
```

------------------------------------------------------------------------

# Theme Package

输出结构：

    LOOKA_THEME_PACK

    ├── assets
    │
    │── stamp001.webp
    │── stamp002.webp
    │
    ├── theme.json
    │
    └── metadata.json

------------------------------------------------------------------------

# Theme JSON

``` json
{
 "theme_id":"cat_trip_001",
 "name":"Cat Travel",
 "style":"LOOKA Cute Sticker",
 "count":24,
 "assets":[]
}
```

------------------------------------------------------------------------

# API Contract

## Generate Theme Stamp

POST

    /api/ai/theme-stamp/generate

Request:

``` json
{
 "theme_name":"Dog Birthday",
 "description":"Cute dog birthday stickers",
 "count":24
}
```

Response:

``` json
{
 "job_id":"stamp_job_001",
 "status":"processing"
}
```

------------------------------------------------------------------------

# Job Status

GET

    /api/ai/theme-stamp/jobs/{job_id}

Status:

    pending

    processing

    review

    completed

    published

------------------------------------------------------------------------

# Asset API

Create Asset:

POST

    /api/assets/stamps

Get Theme:

GET

    /api/themes/{theme_id}

------------------------------------------------------------------------

# Database Models

## stamp_assets

    asset_id
    theme_id
    name
    image_url
    category
    tags
    style
    width
    height
    format
    created_at

## stamp_themes

    theme_id
    name
    description
    cover_image
    asset_count
    style
    created_at

------------------------------------------------------------------------

# GPT Function Calling

Tool:

    generate_theme_stamp

Trigger:

用户要求：

-   创建主题印章
-   制作手帐贴纸
-   生成个人主题包

Example:

用户：

"帮我做一套我的猫咪旅行印章"

GPT:

调用:

generate_theme_stamp

------------------------------------------------------------------------

# Constraints

必须：

-   256×256
-   WebP
-   Transparent
-   LOOKA Cute Sticker

禁止：

-   大尺寸资产
-   复杂背景
-   水印
-   图片文字

------------------------------------------------------------------------

# Claude Implementation Goal

请实现：

1.  Skill Handler
2.  Image Processing Pipeline
3.  Asset Library API
4.  Theme Database
5.  GPT Function Calling Connector
6.  Sticker Picker 数据接口

最终实现：

用户一句话生成主题

↓

GPT 生图

↓

Skill 自动转换

↓

进入 LOOKA 印章库

↓

用户应用到日历
