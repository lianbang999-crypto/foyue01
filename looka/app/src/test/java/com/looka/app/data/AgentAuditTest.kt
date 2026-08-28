package com.looka.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §132 A1：撤销快照 roundtrip 防字段漂移。
 *
 * 快照是手写 JSON 序列化 —— 实体加字段而这里漏改时，撤销会静默丢数据。
 * 防线：样本给**每个字段非默认值**，roundtrip 后用 data class equals 全字段比对；
 * 漏序列化的字段会以默认值回来，equals 必挂。
 */
class AgentAuditTest {

    private val event = EventSeries(
        id = 7, title = "复诊", categoryId = 3, allDay = true,
        startDay = 20690, endDay = 20691, startMin = 540, endMin = 600,
        location = "医院", memo = "带病历", freq = FREQ_WEEKLY, interval = 2,
        weekdays = 0b0101, monthlyByWeekday = true, untilDay = 20800,
        uid = "uid-e7", updatedAt = 111L, dirty = false, deleted = true
    )

    private val task = Task(
        id = 5, title = "交报告", done = true, dueDay = 20700, memo = "初稿即可",
        createdAt = 22L, listUid = "list-work", starred = true, doneAt = 33L,
        labels = "urgent", sortOrder = 9, uid = "uid-t5", updatedAt = 44L,
        dirty = false, deleted = true
    )

    private val note = Note(
        id = 9, title = "购物", content = "牛奶×2", listUid = "nlist-x",
        createdAt = 55L, sortOrder = 3, updatedAt = 66L, uid = "uid-n9",
        dirty = false, deleted = true
    )

    @Test
    fun `日程快照 roundtrip 全字段`() {
        val d = AgentOpSnapshot.decode(AgentOpSnapshot.ofEvent(event, created = true))!!
        assertEquals("event", d.kind)
        assertTrue(d.created)
        assertEquals(event, d.event)
    }

    @Test
    fun `任务快照 roundtrip 全字段`() {
        val d = AgentOpSnapshot.decode(AgentOpSnapshot.ofTask(task))!!
        assertEquals("task", d.kind)
        assertEquals(false, d.created)
        assertEquals(task, d.task)
    }

    @Test
    fun `笔记快照 roundtrip 全字段`() {
        val d = AgentOpSnapshot.decode(AgentOpSnapshot.ofNote(note))!!
        assertEquals("note", d.kind)
        assertEquals(note, d.note)
    }

    @Test
    fun `坏快照返回 null 而不是抛异常`() {
        assertNull(AgentOpSnapshot.decode(""))
        assertNull(AgentOpSnapshot.decode("{"))
        assertNull(AgentOpSnapshot.decode("""{"kind":"alien","o":{}}"""))
        assertNull(AgentOpSnapshot.decode("""{"kind":"event","o":{"id":1}}"""))   // 字段残缺
    }

    /** §132 A4：等级映射按母档 §12 —— remember 低风险、创建/主题可逆、改删是事实变更 */
    @Test
    fun `风险等级映射`() {
        assertEquals("L1", riskLevelOf("remember"))
        assertEquals("L2", riskLevelOf("create_event"))
        assertEquals("L2", riskLevelOf("create_task"))
        assertEquals("L2", riskLevelOf("theme"))
        assertEquals("L3", riskLevelOf("update_event"))
        assertEquals("L3", riskLevelOf("delete_note"))
    }
}
