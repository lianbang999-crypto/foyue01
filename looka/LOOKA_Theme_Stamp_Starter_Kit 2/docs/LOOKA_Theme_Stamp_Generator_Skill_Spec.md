# LOOKA Theme Stamp Generator Skill 技术规范

## 1. Skill 名称
`looka_theme_stamp_generator`

## 2. 功能目标
根据用户自然语言请求，产出 LOOKA 可直接接入的主题印章包，包括：
- 24~40 枚印章资产
- theme.json
- metadata.json
- 可选预览图

## 3. 输入参数
```json
{
  "theme": "春日樱花",
  "style": "LOOKA Cute Lifestyle Sticker",
  "count": 24,
  "language": "zh-CN",
  "constraints": {
    "transparent_background": true,
    "size": {"width": 256, "height": 256},
    "format": "webp"
  }
}
```

## 4. 内部流程
### Step 1: analyze_request
提取主题、核心元素、情绪方向、使用场景。

### Step 2: build_stamp_blueprint
输出一个结构化蓝图列表，每个印章包含：name / object / action / category / tags。

### Step 3: generate_assets
逐个生成 256×256 透明 WebP。

### Step 4: run_qc
检查：
- 尺寸正确
- 背景透明
- 风格统一
- 不带文字、水印、复杂背景

### Step 5: emit_theme_package
打包为 LOOKA 可用资源结构。

## 5. 输出结构
```text
LOOKA_THEME_PACK/
├── assets/
│   └── stamps/
├── data/
│   ├── theme.json
│   └── metadata.json
└── preview/
```

## 6. 约束
- 默认数量 24，最大 40
- 所有资产统一 256×256
- 禁止复杂背景
- 禁止在贴纸上直接写字
- 鼓励统一角色与统一色彩逻辑

## 7. App 对接方式
- App 只依赖 theme.json + metadata.json + 资源路径
- 生成任务异步化，前端以 Job 模式轮询状态
- 发布前可插入人工审核节点
