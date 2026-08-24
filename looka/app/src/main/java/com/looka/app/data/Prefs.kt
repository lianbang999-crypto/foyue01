package com.looka.app.data

import android.content.Context
import android.content.SharedPreferences

/** 应用偏好：AI / 日历显示 / 新建默认值 / 账号同步 / 主题 / 语言 / 提醒 / 更新 */
object Prefs {

    // 自定义 Key 默认为空：默认走 Looka 服务端代理（对话不限次，Key 不进客户端）
    const val DEFAULT_API_KEY = ""
    const val DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1"
    const val DEFAULT_MODEL = "Qwen/Qwen2.5-7B-Instruct"
    const val DEFAULT_SERVER = "https://looka.foyue.org"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("looka_prefs", Context.MODE_PRIVATE)

    // ---- AI ----
    fun apiKey(c: Context) = sp(c).getString("api_key", DEFAULT_API_KEY)!!
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("api_key", v).apply()

    fun baseUrl(c: Context) = sp(c).getString("base_url", DEFAULT_BASE_URL)!!
    fun setBaseUrl(c: Context, v: String) = sp(c).edit().putString("base_url", v).apply()

    fun model(c: Context) = sp(c).getString("model", DEFAULT_MODEL)!!
    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v).apply()

    /** 隐私：允许小鹿读取日程/任务作为上下文（S9，默认开） */
    fun aiReadAgenda(c: Context) = sp(c).getBoolean("ai_read_agenda", true)
    fun setAiReadAgenda(c: Context, v: Boolean) = sp(c).edit().putBoolean("ai_read_agenda", v).apply()

    /** 隐私：允许日记润色上传正文（S9，默认关——日记最私密） */
    fun aiDiaryUpload(c: Context) = sp(c).getBoolean("ai_diary_upload", false)
    fun setAiDiaryUpload(c: Context, v: Boolean) = sp(c).edit().putBoolean("ai_diary_upload", v).apply()

    // ---- 语言（I 批） ----
    /** system / zh-CN / zh-TW / en */
    fun language(c: Context) = sp(c).getString("language", "system")!!
    fun setLanguage(c: Context, v: String) = sp(c).edit().putString("language", v).apply()

    /** S2（§64，照抄 Lifebear）：日程文字大小档。0=大(默认,一格约5条) 1=中 2=小 */
    fun eventTextSize(c: Context) = sp(c).getInt("event_text_size", 0)
    fun setEventTextSize(c: Context, v: Int) { sp(c).edit().putInt("event_text_size", v).apply(); markSettingsDirty(c) }

    /** 12 小时制：null = 跟随语言默认（en 开 / 中文关） */
    fun time12h(c: Context): Boolean? = when (sp(c).getInt("time_12h", -1)) {
        1 -> true; 0 -> false; else -> null
    }
    fun setTime12h(c: Context, v: Boolean) = sp(c).edit().putInt("time_12h", if (v) 1 else 0).apply()

    /** 农历显示：null = 跟随语言默认（中文开 / en 关） */
    fun showLunarRaw(c: Context): Boolean? = when (sp(c).getInt("show_lunar", -1)) {
        1 -> true; 0 -> false; else -> null
    }
    fun setShowLunar(c: Context, v: Boolean) { sp(c).edit().putInt("show_lunar", if (v) 1 else 0).apply(); markSettingsDirty(c) }

    // ---- 日历显示 ----
    /** 一周从周一开始（false = 周日开始） */
    fun weekStartMonday(c: Context) = sp(c).getBoolean("week_start_mon", true)
    fun setWeekStartMonday(c: Context, v: Boolean) { sp(c).edit().putBoolean("week_start_mon", v).apply(); markSettingsDirty(c) }

    /** 休日星期位掩码 bit0=周一…bit6=周日，休日以红色强调 */
    fun holidayMask(c: Context) = sp(c).getInt("holiday_mask", 1 shl 6)
    fun setHolidayMask(c: Context, v: Int) { sp(c).edit().putInt("holiday_mask", v).apply(); markSettingsDirty(c) }

    /** 已完成任务是否显示在日历 */
    fun showDoneTasks(c: Context) = sp(c).getBoolean("show_done_tasks", true)
    fun setShowDoneTasks(c: Context, v: Boolean) { sp(c).edit().putBoolean("show_done_tasks", v).apply(); markSettingsDirty(c) }

    // Sticker 模块 §6.1（CAL-060 列表项）：绑定日程的印章是否在旁边显示标题气泡
    fun stampTitle(c: Context) = sp(c).getBoolean("stamp_title", true)
    fun setStampTitle(c: Context, v: Boolean) { sp(c).edit().putBoolean("stamp_title", v).apply(); markSettingsDirty(c) }

    // ---- 新建默认值 ----
    fun defaultCategoryId(c: Context) = sp(c).getLong("def_category", 1L)
    fun setDefaultCategoryId(c: Context, v: Long) = sp(c).edit().putLong("def_category", v).apply()

    fun defaultAllDay(c: Context) = sp(c).getBoolean("def_all_day", false)
    fun setDefaultAllDay(c: Context, v: Boolean) = sp(c).edit().putBoolean("def_all_day", v).apply()

    /** 时间日程默认提醒（提前分钟，-1 = 无） */
    fun defTimedReminderMin(c: Context) = sp(c).getInt("def_timed_reminder", 15)
    fun setDefTimedReminderMin(c: Context, v: Int) = sp(c).edit().putInt("def_timed_reminder", v).apply()

    /** 全天日程默认提醒（提前天数，-1 = 无） */
    fun defAllDayReminderDays(c: Context) = sp(c).getInt("def_allday_reminder_days", 0)
    fun setDefAllDayReminderDays(c: Context, v: Int) = sp(c).edit().putInt("def_allday_reminder_days", v).apply()

    /** 全天日程默认提醒时刻（分钟） */
    fun defAllDayReminderTime(c: Context) = sp(c).getInt("def_allday_reminder_time", 480)
    fun setDefAllDayReminderTime(c: Context, v: Int) = sp(c).edit().putInt("def_allday_reminder_time", v).apply()

    // ---- 任务提醒（三批新增） ----
    /** 到期任务提醒开关 */
    fun taskRemOn(c: Context) = sp(c).getBoolean("task_rem_on", true)
    fun setTaskRemOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("task_rem_on", v).apply()

    /** 到期任务提醒时刻（分钟，默认 9:00） */
    fun taskRemMin(c: Context) = sp(c).getInt("task_rem_min", 9 * 60)
    fun setTaskRemMin(c: Context, v: Int) = sp(c).edit().putInt("task_rem_min", v).apply()

    /** 逾期任务转移弹窗：最近提示的日期（每天最多一次） */
    fun carryPromptDay(c: Context) = sp(c).getLong("carry_prompt_day", 0L)
    fun setCarryPromptDay(c: Context, v: Long) = sp(c).edit().putLong("carry_prompt_day", v).apply()

    // ---- 印章 ----
    /** 最近使用的印章资产 id（CSV，最多 12 个） */
    fun recentStamps(c: Context): List<String> =
        sp(c).getString("recent_stamps", "")!!.split(",").filter { it.isNotBlank() }
    fun pushRecentStamp(c: Context, assetId: String) {
        val list = (listOf(assetId) + recentStamps(c).filter { it != assetId }).take(12)
        sp(c).edit().putString("recent_stamps", list.joinToString(",")).apply()
    }

    // ---- 主题（九色鹿） ----
    fun themeIndex(c: Context) = sp(c).getInt("theme_index", 0)
    fun setThemeIndex(c: Context, v: Int) = sp(c).edit().putInt("theme_index", v).apply()

    // ---- 账号 / 同步 ----
    fun serverUrl(c: Context) = sp(c).getString("server_url", DEFAULT_SERVER)!!
    fun setServerUrl(c: Context, v: String) = sp(c).edit().putString("server_url", v).apply()

    fun authToken(c: Context): String? = sp(c).getString("auth_token", null)
    fun setAuthToken(c: Context, v: String?) = sp(c).edit().putString("auth_token", v).apply()

    fun accountEmail(c: Context) = sp(c).getString("account_email", "")!!
    fun setAccountEmail(c: Context, v: String) = sp(c).edit().putString("account_email", v).apply()

    /** 昵称（与自知录共用同一账号的 users.nickname）。空 = 未设置，界面回退显示账号。 */
    fun nickname(c: Context) = sp(c).getString("nickname", "")!!
    fun setNickname(c: Context, v: String) = sp(c).edit().putString("nickname", v).apply()
    /** 界面上「怎么称呼这个用户」：有昵称用昵称，没有就用账号 */
    fun displayName(c: Context) = nickname(c).ifBlank { accountEmail(c) }

    // ===== D1（§52）：小鹿记事本 —— 用户主动说过的长期偏好，JSON 数组存储，随 settings 上云 =====
    fun deerFacts(c: Context): List<String> = try {
        val arr = org.json.JSONArray(sp(c).getString("deer_facts", "[]"))
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    fun setDeerFacts(c: Context, v: List<String>) {
        val arr = org.json.JSONArray(); v.take(30).forEach { arr.put(it) }
        sp(c).edit().putString("deer_facts", arr.toString()).apply()
        markSettingsDirty(c)
    }

    fun addDeerFact(c: Context, fact: String) {
        val cur = deerFacts(c)
        if (fact.isBlank() || cur.contains(fact)) return
        setDeerFacts(c, cur + fact)
    }

    // ===== P5-1 设置上云：设置是同步实体（kind='settings'，uid 固定 'settings'）=====
    // 两端必须看到同一本日历 —— 周起始/农历/节假日/显示已完成 改一处、处处生效
    fun settingsDirty(c: Context) = sp(c).getBoolean("st_dirty", false)
    fun setSettingsDirty(c: Context, v: Boolean) = sp(c).edit().putBoolean("st_dirty", v).apply()
    fun settingsUpdatedAt(c: Context) = sp(c).getLong("st_updated", 0L)
    fun setSettingsUpdatedAt(c: Context, v: Long) = sp(c).edit().putLong("st_updated", v).apply()
    fun markSettingsDirty(c: Context) = sp(c).edit()
        .putBoolean("st_dirty", true).putLong("st_updated", System.currentTimeMillis()).apply()

    // ⚠️ 订阅状态唯一真值源是 PlanState（P2-A，2026-08-22）。
    // 这三个函数只给 PlanState 做持久化用，页面一律读 PlanState.isPro / PlanState.plan。
    fun plan(c: Context) = sp(c).getString("plan", "free")!!
    fun setPlan(c: Context, v: String) = sp(c).edit().putString("plan", v).apply()
    fun planExpiry(c: Context) = sp(c).getLong("plan_expiry", 0L)
    fun setPlanExpiry(c: Context, v: Long) = sp(c).edit().putLong("plan_expiry", v).apply()
    fun planSyncedAt(c: Context) = sp(c).getLong("plan_synced_at", 0L)
    fun setPlanSyncedAt(c: Context, v: Long) = sp(c).edit().putLong("plan_synced_at", v).apply()
    /** @deprecated 页面请用 PlanState.isPro（带本地过期判断 + 自动重组） */
    fun isPro(c: Context) = PlanState.isPro
    // 支付等待中：点「开通 Pro」跳走的时刻；开通成功或超时清零（P2-A9）
    fun payPendingSince(c: Context) = sp(c).getLong("pay_pending_since", 0L)
    fun setPayPendingSince(c: Context, v: Long) = sp(c).edit().putLong("pay_pending_since", v).apply()

    /** E2 编辑草稿（防进程被杀丢内容）：500ms 防抖写入，保存成功后清除 */
    /** 自创主题主色（ARGB，0 = 未设置过） */
    fun customThemeColor(c: Context) = sp(c).getLong("custom_theme", 0xFF55B04BL)
    fun setCustomThemeColor(c: Context, v: Long) = sp(c).edit().putLong("custom_theme", v).apply()

    fun draft(c: Context, key: String) = sp(c).getString("draft_$key", "")!!
    fun setDraft(c: Context, key: String, v: String) =
        sp(c).edit().putString("draft_$key", v).apply()
    fun clearDraft(c: Context, key: String) = sp(c).edit().remove("draft_$key").apply()

    /** 小鹿使用的模型档：standard / premium / flagship */
    fun aiTier(c: Context) = sp(c).getString("ai_tier", "standard")!!
    fun setAiTier(c: Context, v: String) = sp(c).edit().putString("ai_tier", v).apply()

    /** 鹿角余额本地缓存（只为离线也能显示，真值以服务端为准） */
    fun antler(c: Context) = sp(c).getInt("antler", -1)
    fun setAntler(c: Context, v: Int) = sp(c).edit().putInt("antler", v).apply()

    /**
     * §98 E3：笔记排序档 —— 0 更新日 / 1 创建日 / 2 笔记名。
     * 默认 1（创建日），与实机「並び替え」对话框的默认选中一致。
     */
    fun noteSort(c: Context) = sp(c).getInt("note_sort", 1)
    fun setNoteSort(c: Context, v: Int) = sp(c).edit().putInt("note_sort", v).apply()

    fun lastPullMs(c: Context) = sp(c).getLong("last_pull_ms", 0L)
    fun setLastPullMs(c: Context, v: Long) = sp(c).edit().putLong("last_pull_ms", v).apply()

    // §97 TL-006：复合游标的另两段。老版本升级上来时为空 ——
    // 空值会让服务端把同毫秒的记录**重发**一遍（幂等，安全方向），不会漏。
    fun lastPullKind(c: Context): String = sp(c).getString("last_pull_kind", "") ?: ""
    fun lastPullUid(c: Context): String = sp(c).getString("last_pull_uid", "") ?: ""
    fun setLastPull(c: Context, ms: Long, kind: String, uid: String) = sp(c).edit()
        .putLong("last_pull_ms", ms).putString("last_pull_kind", kind)
        .putString("last_pull_uid", uid).apply()

    // ---- 应用更新（五批） ----
    /** 最近自动检查更新的日期（每天至多一次） */
    fun updateCheckDay(c: Context) = sp(c).getLong("update_check_day", 0L)
    fun setUpdateCheckDay(c: Context, v: Long) = sp(c).edit().putLong("update_check_day", v).apply()

    /** DownloadManager 下载任务 id（-1 = 无） */
    fun apkDownloadId(c: Context) = sp(c).getLong("apk_download_id", -1L)
    fun setApkDownloadId(c: Context, v: Long) = sp(c).edit().putLong("apk_download_id", v).apply()

    /** 待安装 APK 的校验值与版本 */
    fun apkSha256(c: Context) = sp(c).getString("apk_sha256", "")!!
    fun setApkSha256(c: Context, v: String) = sp(c).edit().putString("apk_sha256", v).apply()

    // ---- 系统日历聚合 ----
    fun showSysCal(c: Context) = sp(c).getBoolean("show_sys_cal", false)
    fun setShowSysCal(c: Context, v: Boolean) = sp(c).edit().putBoolean("show_sys_cal", v).apply()

    /** 被隐藏的系统日历 id 集合 */
    fun hiddenSysCals(c: Context): Set<String> = sp(c).getStringSet("hidden_sys_cals", emptySet())!!
    fun setHiddenSysCals(c: Context, v: Set<String>) = sp(c).edit().putStringSet("hidden_sys_cals", v).apply()
}
