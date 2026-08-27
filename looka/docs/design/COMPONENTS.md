# Looka 组件注册表 v1（§120 P2 · 依《全站UIUX统一规划》F 章建立）

> 规则（F1）：功能页面只能组合本表组件与模式；需要新交互先在此登记合同，再接入页面。
> 不得在页面文件里临时写仅本页可用的弹窗、按钮或价格卡（H3 check_component_usage 的拦截对象）。
> 每项按 F2 字段登记；states 至少覆盖 默认/按压/加载/禁用/错误/完成 中适用者。

## 导航族
| component_id | 位置 | purpose | 关键行为 |
|---|---|---|---|
| nav.topbar.v1 | `common/Ui.kt LookaTopBar` | 页面顶栏：返回+标题+动作区 | 标题可选点击(onTitleClick)；返回=popBackStack |
| nav.row.v1 | `common/Ui.kt NavRow` | 设置/列表导航行：主标签16sp+摘要12sp灰下行、无箭头 | rowClick 整行浅灰按压；44dp 最小高 |
| nav.section.v1 | `more/MoreScreen.kt SectionLabel` | 分组标题 12sp 灰 | 纯展示 |
| nav.save.v1 | `common/Ui.kt SaveButton` | 顶栏深色小矩形保存（5dp 圆角） | enabled 灰化；防连点由调用方 saving 守卫 |

## 浮层族
| component_id | 位置 | purpose | 关键行为 |
|---|---|---|---|
| overlay.confirm.v1 | `common/Ui.kt ConfirmDialog` | 危险/决策确认：标题20sp+正文+右下双文字键 | scrim 60%；confirmText 可定制；§113 弹窗语法 |
| overlay.dlgtitle.v1 | `common/Ui.kt DlgTitle` | 全站 Dialog 标题唯一写法（20sp SemiBold+提 scrim） | 所有 AlertDialog 的 title 必须用它 |
| overlay.radio.v1 | `common/Ui.kt RadioDialog` | 单选弹窗：选择即提交关闭 | select-and-close；全局 3dp 圆角 |
| overlay.datepicker.v1 | `common/Ui.kt LookaDatePicker` | 字段级日期选择 | Done 只提交字段值，不等于对象保存 |
| overlay.timepicker.v1 | `common/Ui.kt LookaTimePicker` | 字段级时间选择 | 同上 |

## 状态族
| component_id | 位置 | purpose | 关键行为 |
|---|---|---|---|
| state.loading.v1 | `common/Ui.kt DeerLoading` | 统一加载态：小鹿呼吸缩放 | 真实等待才用；reduce-motion 自动停 |
| state.empty.v1 | `common/Ui.kt EmptyDeer` | 空状态：标题+小鹿+引导行 | 不遮中央＋ |
| state.hairline.v1 | `common/Ui.kt Hairline` | 0.6dp 低对比结构线 | 只做结构，不围卡片 |

## 商业族
| component_id | 位置 | purpose | 关键行为 |
|---|---|---|---|
| commerce.purchase-confirm.v1 | `more/ExtraScreens.kt ShopScreen 内` | 购买确认：价格+余额 42→12+差额提示 | 不足给获得方式；确认后原子扣费 |
| commerce.locked-hint.v1 | `common/StickerPicker.kt LockedPackHint` | 锁定包引导（不承载购买） | 只指路装扮商店；创作场景不弹商业流程 |
| commerce.wallet-ledger.v1 | `more/ExtraScreens.kt AntlerScreen 内` | 鹿角流水：原因+日期+±数 | 最近10条；数字来自 /api/antler |

## 附件族
| component_id | 位置 | purpose | 关键行为 |
|---|---|---|---|
| attach.section.v1 | `common/Attachments.kt AttachmentSection` | 附件区：缩略图横滚+拍照/相册+预览删除 | 未同步灰点角标；provider 异常降级隐藏拍照；宿主键见 F4 合同 |

## 手势族
| component_id | 位置 | purpose | 关键行为 |
|---|---|---|---|
| gesture.row.v1 | `common/ListGestures.kt listRowGestures` | 长按拖排序+左滑删（35% 阈值） | 删除必走 softDelete 撤销；智能视图禁排序 |
| gesture.snapshot.v1 | `common/ListGestures.kt rememberSnapshotOrder` | 快照式列表（勾掉不消失） | 前驱锚定；双端同构（Web snapshotOrder） |

## AI 族
| component_id | 位置 | purpose | 关键行为 |
|---|---|---|---|
| ai.proposal.v1 | `ai/AiChatScreen.kt 批量确认卡` | 结构化草稿确认：逐条勾选后执行 | 删除必确认；≥3 条必确认；执行后可撤销 |
| ai.memory.v1 | `more/ExtraScreens.kt DeerMemorySection` | 小鹿记忆：可看可逐条删 | 在「小鹿设置」内 |

## 尚未建立（下批候选）
- overlay.actionsheet.v1（拍照/相册来源选择目前用 AlertDialog 形态，C3 建议 Bottom Action Sheet）
- state.offline-banner.v1 / state.inline-error.v1
- commerce.plan-card.v1（方案页对比卡）
- ai.diff-row.v1（修改前后对照行）
