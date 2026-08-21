# LOOKA Theme Stamp Starter Kit

本包用于交给 Claude / 开发同学，完成 LOOKA Theme Stamp 系统接入。

## 包含内容
- `assets/stamps/`：24 枚 128×128 WebP 牛牛主题印章
- `data/theme.json`：主题包清单
- `data/metadata.json`：每个印章的元数据
- `data/database_schema.json`：建议数据库结构
- `skill/skill_contract.json`：Theme Stamp Skill 接口约定
- `docs/`：接入规划、Skill 规范、Claude 开发任务拆分

## 统一规范
- 尺寸：128×128
- 格式：WebP
- 背景：透明
- 主题包默认数量：24
- 单次最大生成量：40
