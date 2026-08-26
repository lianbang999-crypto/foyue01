package com.looka.app.util

/**
 * §117 D2：多关键词搜索 —— 空格（含全角空格）分词，词间 AND、词内 contains。
 * 此前是把整句当一个连续子串 contains，"买 牛奶" 搜不到 "买两盒牛奶"。
 * 三端同口径（App 笔记/日记/待办 + Web renderNotes/renderTodos）。
 */
fun matchWords(query: String, vararg fields: String): Boolean {
    val words = query.split(' ', '　').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    return words.all { w -> fields.any { it.contains(w, ignoreCase = true) } }
}
