package com.looka.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

// 重复频率
const val FREQ_NONE = 0
const val FREQ_DAILY = 1
const val FREQ_WEEKLY = 2
const val FREQ_MONTHLY = 3
const val FREQ_YEARLY = 4

fun newUid(): String = UUID.randomUUID().toString()

/** 分类色板（原创配色） */
val CATEGORY_PALETTE = listOf(
    "#E0504A", "#F2913D", "#E3B93A", "#7CB342", "#55B04B", "#3AA9A0",
    "#4A9EDB", "#4A7DDC", "#7E6BD8", "#9C5FC8", "#E077A8", "#A66E4A",
    "#8A8F8E", "#5C6670"
)

/** 旧版 emoji 印章集（保留用于渲染历史数据；新贴一律走图片资产） */
val STAMP_EMOJIS = listOf(
    "🦌", "⭐", "❤️", "🎂", "🎁", "🏃", "🍚", "📚", "💼", "✈️",
    "🏥", "💊", "🎵", "🎮", "🌸", "🌙", "☀️", "🎯", "💰", "📞"
)

/*
 * 云同步公共字段（所有可同步实体）：
 * uid       全局唯一 id（跨设备识别）
 * updatedAt 最后修改时间（LWW 冲突合并依据）
 * dirty     本地有改动待上传
 * deleted   软删除墓碑（同步后各端一致删除）
 */

/** 日历分类：颜色即分类（规格 CAL-040/041） */
@Entity(tableName = "category", indices = [Index(value = ["uid"])])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String,
    val sortOrder: Int = 0,
    val visible: Boolean = true,
    val deletable: Boolean = true,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    val deleted: Boolean = false
)

/** 日程系列：普通日程 = 无重复规则的系列（规格 §11） */
@Entity(tableName = "event_series", indices = [Index(value = ["uid"])])
data class EventSeries(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val categoryId: Long = 1,
    val allDay: Boolean = false,
    val startDay: Long,
    val endDay: Long,
    val startMin: Int = 0,
    val endMin: Int = 0,
    val location: String = "",
    val memo: String = "",
    val freq: Int = FREQ_NONE,
    val interval: Int = 1,
    val weekdays: Int = 0,               // 位掩码 bit0=周一 … bit6=周日
    val monthlyByWeekday: Boolean = false,
    val untilDay: Long = -1L,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    val deleted: Boolean = false
)

/** 重复例外（随所属系列打包同步，自身不带同步字段） */
@Entity(tableName = "event_exception")
data class EventException(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val occurrenceDay: Long,
    val cancelled: Boolean = false,
    val newDay: Long = -1L,
    val title: String? = null,
    val allDay: Boolean? = null,
    val startMin: Int? = null,
    val endMin: Int? = null,
    val categoryId: Long? = null,
    val location: String? = null,
    val memo: String? = null
)

/** 提醒规则（随所属系列打包同步） */
@Entity(tableName = "reminder")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long = 0,
    val minutesBefore: Int = 15,
    val daysBefore: Int = 0,
    val timeOfDayMin: Int = 480,
    val enabled: Boolean = true,
    // A2（§48）：true = 当成闹钟 —— 走闹钟音量持续响直到手动停止；false = 普通提醒响一声
    val alarm: Boolean = false
)

/** 任务清单：带颜色/排序/归档（Lifebear ToDo 的清单层级） */
@Entity(tableName = "task_list", indices = [Index(value = ["uid"])])
data class TaskList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String = "#5C6670",
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val deletable: Boolean = true,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    val deleted: Boolean = false
)

/** 任务：归属清单 + 星标 + 完成时间 + 手动排序（labels 预留给订阅版标签） */
@Entity(
    tableName = "task",
    indices = [Index(value = ["uid"]), Index(value = ["listUid"]), Index(value = ["dueDay"])]
)
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val done: Boolean = false,
    val dueDay: Long = -1L,
    val memo: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val listUid: String = "list-default",
    val starred: Boolean = false,
    val doneAt: Long = -1L,
    val labels: String = "",
    val sortOrder: Long = 0,             // 清单内手动顺序（拖拽重排）
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    val deleted: Boolean = false
)

/** 清单调色盘：8 行 × 6 列（对齐 Lifebear 的大色板） */
// 2026-08-21 对齐 Lifebear：从实机录屏色板采样的 48 色（8 行 × 6 列，色相环螺旋、高饱和）。
// 与旧 Material 色调盘的区别是组织方式：连续流动的贴纸色，不是"8 色相 × 6 明度"。
// ⚠️ 含大量亮色（黄/黄绿/浅青）—— 色块上的文字必须用 onColor() 按亮度选黑白，不能写死白字。
val LIST_PALETTE = listOf(
    "#000000", "#FBFDFC", "#7B6359", "#AB958A", "#EEB19F", "#EE958F",
    "#E67289", "#E13C5E", "#D10021", "#920011", "#5F000B", "#A44702",
    "#DE6A03", "#ED8F1D", "#F7C212", "#F6DC31", "#DFED38", "#C0E30D",
    "#99B218", "#2E822D", "#57B652", "#59DF86", "#17C192", "#0EA66D",
    "#046963", "#0E9199", "#14B2BD", "#5ED3DA", "#90DDF7", "#53C8E9",
    "#1E86C3", "#062389", "#2947A1", "#425BBF", "#697EDB", "#80A5EC",
    "#ADA1EB", "#8272D5", "#6A42D7", "#4F18B4", "#8500C0", "#B852E6",
    "#DE4BE2", "#CB00D8", "#9F0058", "#C10080", "#F05FBE", "#F689CD"
)

/** 笔记 */
@Entity(tableName = "note", indices = [Index(value = ["uid"])])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val uid: String = newUid(),
    val dirty: Boolean = true,
    val deleted: Boolean = false
)

/** 日记：每天一篇；uid 固定为 diary-<day>，跨设备天然合并 */
@Entity(
    tableName = "diary",
    indices = [Index(value = ["day"], unique = true), Index(value = ["uid"])]
)
data class Diary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val mood: Int = 2,
    val content: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val uid: String = "",
    val dirty: Boolean = true,
    val deleted: Boolean = false
)

val MOOD_EMOJIS = listOf("😄", "🙂", "😐", "😞", "😫")

/**
 * 鹿印章：assetId 指向内置图片资产（"base/01_mood_happy"）；
 * assetId 为空时回退渲染 emoji（历史数据）。可选绑定日程（eventUid，规格 CAL-051）。
 */
@Entity(
    tableName = "stamp",
    indices = [Index(value = ["uid"]), Index(value = ["day"])]
)
data class Stamp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val emoji: String,
    val day: Long,
    val eventUid: String = "",
    val assetId: String = "",
    // Sticker Canvas v1（§68 二，Lifebear 冻结规格）：格内相对坐标 0~1。
    // -1 = 未摆放（沿用旧的行内小图排列）。位置是实例状态，可反复拖动（Place→Reposition）。
    val posX: Float = -1f,
    val posY: Float = -1f,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    val deleted: Boolean = false
)

/** 日程模板（本机保存，规格 CAL-010 模板入口） */
@Entity(tableName = "template")
data class Template(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val payload: String,           // JSON：字段快照（不含日期）
    val createdAt: Long = System.currentTimeMillis()
)

/** 同步冲突留痕：LWW 覆盖本机未上传修改时，把被覆盖版本存一份（B19 可见化） */
@Entity(tableName = "conflict_log")
data class ConflictLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val title: String,
    val payload: String,           // 被覆盖版本的文本快照（可复制找回）
    val occurredAt: Long = System.currentTimeMillis()
)
