# Claude 接入开发任务拆分

## 总目标
让 Claude 依据本包，直接完成 LOOKA Theme Stamp 模块的前后端接入。

---

## A. 前端任务（App）
### A1. Asset Library 读取
- 读取 `theme.json`
- 读取 `metadata.json`
- 将主题包加载到内存 Store

### A2. Sticker Picker UI
- 主题包列表页
- 主题包详情页（24 枚预览）
- 分类筛选（emotion / life / productivity / event / seasonal）
- 搜索与标签过滤

### A3. 日历/手帐应用
- 选择印章并插入到指定日期/页面
- 支持拖拽、缩放、旋转、删除
- 保存用户操作结果

### A4. 最近使用 / 收藏
- 保存最近使用的印章
- 支持收藏常用主题包

---

## B. 后端任务（服务端）
### B1. 主题包数据模型
建立：
- `stamp_themes`
- `stamp_assets`
- `stamp_tags`
- `generated_theme_jobs`

### B2. API
实现：
- `GET /api/stamps/themes`
- `GET /api/stamps/themes/{theme_id}`
- `GET /api/stamps/assets/{asset_id}`
- `POST /api/stamps/themes/generate`
- `GET /api/stamps/themes/jobs/{job_id}`
- `POST /api/stamps/themes/{theme_id}/publish`

### B3. 存储
- 资源文件可先放对象存储 / CDN
- theme.json / metadata.json 入库或对象存储均可

---

## C. AI / Skill 任务
### C1. 生成请求入口
- 接收用户主题描述
- 标准化为 skill input

### C2. 蓝图生成器
- 将主题拆成 24 个印章动作点子
- 输出结构化 blueprint

### C3. 图像生成管线
- 调用图像生成能力
- 确保统一风格与 128×128 规格

### C4. 自动质检
- 校验尺寸/背景/命名/重复度
- 生成失败时可自动重试

### C5. 发布链路
- 完成主题包 manifest
- 写入 Asset Library

---

## D. 建议里程碑
### M1：静态接入
导入牛牛主题包，在本地页面/测试环境中可浏览。

### M2：交互接入
印章可插入日历并保存。

### M3：服务化
Theme API 与 Asset Library 上线。

### M4：AI 接入
Skill 生成主题包的异步任务跑通。

### M5：发布审核
加入人工审核/自动 QC / 上线机制。

---

## E. Claude 执行提示
建议 Claude 先完成：
1. 数据模型
2. 主题包解析器
3. Sticker Picker UI
4. 日历插入能力
5. AI Skill Job 接口

这样可以先让官方 Demo 包上线，再逐步接入 AI 自动生成。
