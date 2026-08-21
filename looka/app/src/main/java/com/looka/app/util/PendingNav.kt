package com.looka.app.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/** 通知点击 → 日历跳转的轻量信箱（CalendarScreen 消费后清零） */
object PendingNav {
    var day by mutableLongStateOf(-1L)

    fun consume(): Long? {
        val d = day
        return if (d >= 0) { day = -1L; d } else null
    }
}
