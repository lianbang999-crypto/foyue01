package com.looka.app.ui.theme

/**
 * T6（§65）：全站动画节奏 —— 从 Lifebear 操作视频逐帧量出的规格。
 * 三条原则：先确认后转场 · 退比进快 · 框架先到位、内容后淡入。
 * 全站动画时长只允许引用这里，不许再散落魔法数字。
 */
// §120 P2：与《全站统一规划》C4 动效 Token 的映射（数值都在 C4 区间内，无需改动）——
//   PRESS  → motion.pressed  (80–120ms)
//   ENTER  → motion.page 进入 (220–300ms)
//   EXIT   → motion.page 返回（永远快于进入）
//   DIALOG → motion.overlay  (140–200ms)
// 新增动效必须从这里取值；页面私写 tween(毫秒) 视为未登记样式（H2 门禁）。
object Motion {
    /** 按压反馈：先给「我收到了」，再开始动作 */
    const val PRESS = 120

    /** 页面 / 底部面板进入（Lifebear ≈300~400ms，取下沿保留轻快） */
    const val ENTER = 280

    /** 退出 —— 永远比进入快 */
    const val EXIT = 180

    /** 弹窗：淡入 + scale 0.94→1 */
    const val DIALOG = 200

    /** 内容 / 插画分层淡入（在框架到位之后） */
    const val CONTENT = 160
}
