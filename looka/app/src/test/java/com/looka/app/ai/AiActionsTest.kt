package com.looka.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * AiActions.split / parseActions 回归测试。
 *
 * 背景：2026-08-21 线上事故 —— Qwen2.5-7B 输出未闭合的 ```json 围栏 + 畸形 JSON，
 * 旧解析器两条路径都没命中，于是把整段裸 JSON 原样显示给了用户（见 docs/ROADMAP v1.3.2）。
 * 这里的用例直接取自那次的真实回复。
 */
class AiActionsTest {

    /** 事故原样输入：围栏未闭合 + JSON 畸形 + envelope 缺失 */
    private val CRASH_CASE = """
        明天是 22226-8-229，8 周五，，，你没有安排任何行程。

        如果你想添加一些日常活动， ```json
        {"type":"create_event","title":"健身","date":" "2226-8-29 "","end_date":"","start":"00 ","end":" " "","location":"","memo":""}"}}} }
    """.trimIndent()

    @Test
    fun `畸形未闭合围栏 绝不把裸JSON显示给用户`() {
        val (display, actions) = AiActions.split(CRASH_CASE)
        assertFalse("展示文本仍含 JSON 键名：$display", display.contains("create_event"))
        assertFalse("展示文本仍含花括号：$display", display.contains("{"))
        assertFalse("展示文本仍含围栏：$display", display.contains("```"))
        assertTrue("正常散文应保留", display.contains("你没有安排任何行程"))
        // JSON 本身是坏的，解析不出动作是正确结果——重点是别泄露
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `弱模型把 actions 写成 events 也要认`() {
        val raw = """好的～
            ```json
            {"events":[{"type":"create_event","title":"开会","date":"2026-08-22","start":"15:00","end":"16:00"}]}
            ```"""
        val (display, actions) = AiActions.split(raw)
        assertEquals(1, actions.size)
        assertEquals("开会", actions[0].title)
        assertEquals(15 * 60, actions[0].startMin)
        assertFalse(display.contains("{"))
    }

    @Test
    fun `没有 envelope 的裸动作对象也要认`() {
        val raw = """记上了
            ```json
            {"type":"create_task","title":"买牛奶","due":"2026-08-23"}
            ```"""
        val (_, actions) = AiActions.split(raw)
        assertEquals(1, actions.size)
        assertEquals("买牛奶", actions[0].title)
        assertEquals(LocalDate.of(2026, 8, 23).toEpochDay(), actions[0].day)
    }

    @Test
    fun `没打代码块的裸 JSON 也要摘干净`() {
        val raw = """帮你加上了 {"actions":[{"type":"create_event","title":"跑步","date":"2026-08-22"}]} 好啦"""
        val (display, actions) = AiActions.split(raw)
        assertEquals(1, actions.size)
        assertFalse(display.contains("actions"))
        assertTrue(display.contains("帮你加上了"))
        assertTrue(display.contains("好啦"))
    }

    @Test
    fun `离谱年份直接丢弃 不能记到 2226 年`() {
        val raw = """```json
            {"actions":[{"type":"create_event","title":"健身","date":"2226-08-29"}]}
            ```"""
        val (_, actions) = AiActions.split(raw)
        assertEquals(1, actions.size)
        assertEquals("越界年份应回落为未指定(-1)", -1L, actions[0].day)
    }

    @Test
    fun `无标题的占位动作要丢掉`() {
        val raw = """```json
            {"actions":[{"type":"create_event","title":"","date":"2026-08-22"}]}
            ```"""
        val (_, actions) = AiActions.split(raw)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `普通提问原样返回 不误伤`() {
        val raw = "明天是 2026 年 8 月 22 日，周六～🌤️ 目前没有安排哦！"
        val (display, actions) = AiActions.split(raw)
        assertEquals(raw, display)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `用户要的普通代码块要保留`() {
        val raw = "可以用这个正则：\n```regex\n^\\d{4}-\\d{2}$\n```\n试试看"
        val (display, actions) = AiActions.split(raw)
        assertTrue("非动作载荷的代码块被误删了：$display", display.contains("```"))
        assertTrue(display.contains("\\d{4}"))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `全角冒号与个位数时间要能解析`() {
        val raw = """```json
            {"actions":[{"type":"create_event","title":"午饭","date":"2026-08-22","start":"12：5","end":"13:00"}]}
            ```"""
        val (_, actions) = AiActions.split(raw)
        assertEquals(1, actions.size)
        assertEquals(12 * 60 + 5, actions[0].startMin)
        assertEquals(13 * 60, actions[0].endMin)
    }

    @Test
    fun `顶层直接给数组也要认`() {
        val raw = """```json
            [{"type":"create_note","title":"灵感","content":"写点什么"}]
            ```"""
        val (_, actions) = AiActions.split(raw)
        assertEquals(1, actions.size)
        assertEquals("create_note", actions[0].type)
    }
}
