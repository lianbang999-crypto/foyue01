package com.looka.app.ui.theme

/**
 * T6（§65）：全站动画节奏 —— 从 Lifebear 操作视频逐帧量出的规格。
 * 三条原则：先确认后转场 · 退比进快 · 框架先到位、内容后淡入。
 * 全站动画时长只允许引用这里，不许再散落魔法数字。
 */
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
