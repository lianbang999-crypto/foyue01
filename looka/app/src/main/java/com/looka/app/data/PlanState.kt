package com.looka.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.looka.app.net.Api

/**
 * 订阅状态唯一真值源（P2-A，2026-08-22）。
 *
 * 此前的病根：`Prefs.plan` 是一份没有主人的缓存 —— 账号页把 /api/me 的真值
 * 显示了却不落盘（AccountScreen:312），认领成功后也不更新，冷启动/回前台从不刷新。
 * 于是出现「账号页是 Pro、更多页是免费版」的分裂（§三十九）。
 *
 * 现在的规矩：
 *  - 页面一律读 [plan] / [isPro]（Compose State，变了自动重组）
 *  - 任何拿到服务端订阅状态的地方一律调 [apply]（落盘 + 更新 State 一次做完）
 *  - 刷新时机：冷启动 / 回前台(>5 分钟) / 登录 / 兑换 / 认领 / 同步响应 / 手动
 */
object PlanState {
    var plan by mutableStateOf("free"); private set
    var expiry by mutableLongStateOf(0L); private set

    /** Pro 且未过期（本地即可判过期，不必等网络） */
    val isPro: Boolean get() = plan == "pro" && System.currentTimeMillis() < expiry

    /** 冷启动：先从本地缓存恢复（网络刷新随后跟上） */
    fun load(c: Context) {
        plan = Prefs.plan(c)
        expiry = Prefs.planExpiry(c)
    }

    /** 唯一写入口：落盘 + 更新 State，一次做完 */
    fun apply(c: Context, newPlan: String, newExpiry: Long) {
        Prefs.setPlan(c, newPlan)
        Prefs.setPlanExpiry(c, newExpiry)
        Prefs.setPlanSyncedAt(c, System.currentTimeMillis())
        plan = newPlan
        expiry = newExpiry
    }

    /**
     * 从服务端刷新。force=false 时 5 分钟内不重复请求（回前台高频触发要节流）。
     * 静默失败：刷新失败就继续用本地缓存，不打扰用户。
     */
    suspend fun refresh(c: Context, force: Boolean = false) {
        if (Prefs.authToken(c) == null) return
        if (!force && System.currentTimeMillis() - Prefs.planSyncedAt(c) < 5 * 60_000) return
        runCatching {
            val me = Api.me(c)
            apply(c, me.optString("plan", "free"), me.optLong("plan_expiry", 0L))
        }
    }

    /** 退出登录 / 注销 */
    fun clear(c: Context) = apply(c, "free", 0L)
}
