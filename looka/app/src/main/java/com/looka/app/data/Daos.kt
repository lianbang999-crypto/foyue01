package com.looka.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE deleted = 0 ORDER BY sortOrder, id")
    fun all(): Flow<List<Category>>

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int

    @Query("SELECT * FROM category WHERE deleted = 0 ORDER BY sortOrder, id")
    suspend fun list(): List<Category>

    @Query("SELECT * FROM category WHERE uid = :uid")
    suspend fun byUid(uid: String): Category?

    @Query("SELECT * FROM category WHERE dirty = 1")
    suspend fun dirtyList(): List<Category>

    @Query("DELETE FROM category WHERE uid = :uid")
    suspend fun hardDeleteByUid(uid: String)

    @Query("UPDATE category SET dirty = 1")
    suspend fun markAllDirty()

    @Insert
    suspend fun insert(c: Category): Long

    @Update
    suspend fun update(c: Category)

    /** 删除分类前，把该分类下日程归入未分类并标脏 */
    @Query("UPDATE event_series SET categoryId = :to, dirty = 1, updatedAt = :now WHERE categoryId = :from")
    suspend fun reassignEvents(from: Long, to: Long, now: Long)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM event_series WHERE deleted = 0")
    fun allSeries(): Flow<List<EventSeries>>

    @Query("SELECT * FROM event_exception")
    fun allExceptions(): Flow<List<EventException>>

    @Query("SELECT * FROM event_series WHERE deleted = 0")
    suspend fun seriesList(): List<EventSeries>

    @Query("SELECT * FROM event_exception")
    suspend fun exceptionsList(): List<EventException>

    @Query("SELECT * FROM reminder")
    suspend fun remindersList(): List<Reminder>

    @Query("SELECT * FROM reminder")
    fun allReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM event_series WHERE id = :id AND deleted = 0")
    suspend fun series(id: Long): EventSeries?

    /** §132 A3：含墓碑查询 —— 撤销「删除」的回放要能看到已软删对象，series() 的过滤会误判 */
    @Query("SELECT * FROM event_series WHERE id = :id")
    suspend fun seriesAny(id: Long): EventSeries?

    @Query("SELECT * FROM event_series WHERE uid = :uid")
    suspend fun seriesByUid(uid: String): EventSeries?

    @Query("SELECT * FROM event_series WHERE dirty = 1")
    suspend fun dirtySeries(): List<EventSeries>

    @Query("SELECT * FROM reminder WHERE seriesId = :id")
    suspend fun remindersOf(id: Long): List<Reminder>

    @Query("SELECT * FROM event_exception WHERE seriesId = :id")
    suspend fun exceptionsOf(id: Long): List<EventException>

    @Insert
    suspend fun insertSeries(s: EventSeries): Long

    @Update
    suspend fun updateSeries(s: EventSeries)

    @Query("DELETE FROM event_series WHERE id = :id")
    suspend fun hardDeleteSeries(id: Long)

    @Query("UPDATE event_series SET dirty = 1, updatedAt = :now WHERE id = :id")
    suspend fun touchSeries(id: Long, now: Long)

    @Query("UPDATE event_series SET dirty = 1")
    suspend fun markAllDirty()

    @Insert
    suspend fun insertException(e: EventException): Long

    @Query("DELETE FROM event_exception WHERE seriesId = :sid AND occurrenceDay = :day")
    suspend fun deleteException(sid: Long, day: Long)

    @Query("DELETE FROM event_exception WHERE seriesId = :sid AND occurrenceDay >= :fromDay")
    suspend fun deleteExceptionsFrom(sid: Long, fromDay: Long)

    @Query("DELETE FROM event_exception WHERE seriesId = :sid")
    suspend fun deleteExceptionsOf(sid: Long)

    @Insert
    suspend fun insertReminder(r: Reminder)

    @Query("DELETE FROM reminder WHERE seriesId = :sid")
    suspend fun deleteRemindersOf(sid: Long)
}

@Dao
interface TaskListDao {
    @Query("SELECT * FROM task_list WHERE deleted = 0 ORDER BY sortOrder, id")
    fun all(): Flow<List<TaskList>>

    @Query("SELECT COUNT(*) FROM task_list")
    suspend fun count(): Int

    @Query("SELECT * FROM task_list WHERE uid = :uid")
    suspend fun byUid(uid: String): TaskList?

    @Query("SELECT * FROM task_list WHERE deleted = 0")
    suspend fun listAll(): List<TaskList>

    @Query("SELECT * FROM task_list WHERE dirty = 1")
    suspend fun dirtyList(): List<TaskList>

    @Query("DELETE FROM task_list WHERE uid = :uid")
    suspend fun hardDeleteByUid(uid: String)

    @Query("UPDATE task_list SET dirty = 1")
    suspend fun markAllDirty()

    @Insert
    suspend fun insert(l: TaskList): Long

    @Update
    suspend fun update(l: TaskList)
    /** §99 I7：撤销专用 —— 行可能已被同步确认后物理删除，@Update 影响 0 行且不插回 */
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(x: TaskList)

    /** §99 I6：长按拖拽写回手动顺序 */
    @Query("UPDATE task_list SET sortOrder = :order, dirty = 1, updatedAt = :now WHERE uid = :uid")
    suspend fun setSortOrder(uid: String, order: Int, now: Long)

}

@Dao
interface TaskDao {
    /** 删除清单时把任务移入默认清单并标脏 */
    @Query("UPDATE task SET listUid = :to, dirty = 1, updatedAt = :now WHERE listUid = :from AND deleted = 0")
    suspend fun reassignList(from: String, to: String, now: Long)

    @Query("SELECT * FROM task WHERE deleted = 0 ORDER BY done ASC, sortOrder ASC, id ASC")
    fun all(): Flow<List<Task>>

    /** 拖拽重排：按 uid 写入新顺序 */
    @Query("UPDATE task SET sortOrder = :order, dirty = 1, updatedAt = :now WHERE uid = :uid")
    suspend fun setSortOrder(uid: String, order: Long, now: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM task")
    suspend fun maxSortOrder(): Long

    /** §132 A3：含墓碑查询（撤销回放用，理由同 EventDao.seriesAny） */
    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun byIdAny(id: Long): Task?

    /** 逾期未完成（转移弹窗 + 任务提醒用） */
    @Query("SELECT * FROM task WHERE deleted = 0 AND done = 0 AND dueDay >= 0")
    suspend fun openDueList(): List<Task>

    /** 逾期任务整体移到今天 */
    @Query("UPDATE task SET dueDay = :today, dirty = 1, updatedAt = :now WHERE deleted = 0 AND done = 0 AND dueDay >= 0 AND dueDay < :today")
    suspend fun carryOverdueTo(today: Long, now: Long)

    @Query("SELECT * FROM task WHERE deleted = 0")
    suspend fun listAll(): List<Task>

    @Query("SELECT * FROM task WHERE uid = :uid")
    suspend fun byUid(uid: String): Task?

    @Query("SELECT * FROM task WHERE dirty = 1")
    suspend fun dirtyList(): List<Task>

    @Query("DELETE FROM task WHERE uid = :uid")
    suspend fun hardDeleteByUid(uid: String)

    /**
     * §97 TL-002：撤销专用。原来撤销走 @Update —— 行若已被同步确认后物理删除，
     * UPDATE 影响 0 行且**不会插回**，用户在 UI 允许的 5 秒内点撤销却什么也没发生。
     * REPLACE 语义保证「还在就更新、没了就插回」。
     */
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(t: Task)

    @Query("UPDATE task SET dirty = 1")
    suspend fun markAllDirty()

    @Insert
    suspend fun insert(t: Task): Long

    @Update
    suspend fun update(t: Task)

    @Query("UPDATE task SET deleted = 1, dirty = 1, updatedAt = :now WHERE done = 1 AND deleted = 0")
    suspend fun clearDone(now: Long)
}

@Dao
interface NoteListDao {
    @Query("SELECT * FROM note_list WHERE deleted = 0 ORDER BY sortOrder, id")
    fun all(): Flow<List<NoteList>>

    @Query("SELECT COUNT(*) FROM note_list")
    suspend fun count(): Int

    @Query("SELECT * FROM note_list WHERE uid = :uid")
    suspend fun byUid(uid: String): NoteList?

    @Query("SELECT * FROM note_list WHERE deleted = 0")
    suspend fun listAll(): List<NoteList>

    @Query("SELECT * FROM note_list WHERE dirty = 1")
    suspend fun dirtyList(): List<NoteList>

    @Query("DELETE FROM note_list WHERE uid = :uid")
    suspend fun hardDeleteByUid(uid: String)

    @Query("UPDATE note_list SET dirty = 1")
    suspend fun markAllDirty()

    @Insert
    suspend fun insert(l: NoteList): Long

    @Update
    suspend fun update(l: NoteList)
    /** §99 I7：撤销专用 —— 行可能已被同步确认后物理删除，@Update 影响 0 行且不插回 */
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(x: NoteList)

    /** §99 I6：长按拖拽写回手动顺序 */
    @Query("UPDATE note_list SET sortOrder = :order, dirty = 1, updatedAt = :now WHERE uid = :uid")
    suspend fun setSortOrder(uid: String, order: Int, now: Long)

}

@Dao
interface NoteDao {
    @Query("SELECT * FROM note WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun all(): Flow<List<Note>>

    @Query("SELECT * FROM note WHERE deleted = 0")
    suspend fun listAll(): List<Note>

    @Query("SELECT * FROM note WHERE id = :id AND deleted = 0")
    suspend fun byId(id: Long): Note?

    /** §132 A3：含墓碑查询（撤销回放用，理由同 EventDao.seriesAny） */
    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun byIdAny(id: Long): Note?

    @Query("SELECT * FROM note WHERE uid = :uid")
    suspend fun byUid(uid: String): Note?

    @Query("SELECT * FROM note WHERE dirty = 1")
    suspend fun dirtyList(): List<Note>

    @Query("DELETE FROM note WHERE uid = :uid")
    suspend fun hardDeleteByUid(uid: String)

    @Query("UPDATE note SET listUid = :to, dirty = 1, updatedAt = :ts WHERE listUid = :from")
    suspend fun reassignList(from: String, to: String, ts: Long)

    @Query("UPDATE note SET dirty = 1")
    suspend fun markAllDirty()

    @Insert
    suspend fun insert(n: Note): Long

    @Update
    suspend fun update(n: Note)
    /** §99 I7：撤销专用 —— 行可能已被同步确认后物理删除，@Update 影响 0 行且不插回 */
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(x: Note)

    /** §99 I6：长按拖拽写回手动顺序 */
    @Query("UPDATE note SET sortOrder = :order, dirty = 1, updatedAt = :now WHERE uid = :uid")
    suspend fun setSortOrder(uid: String, order: Long, now: Long)

}

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary WHERE deleted = 0 ORDER BY day DESC")
    fun all(): Flow<List<Diary>>

    @Query("SELECT * FROM diary WHERE deleted = 0")
    suspend fun listAll(): List<Diary>

    @Query("SELECT * FROM diary WHERE day = :day AND deleted = 0")
    suspend fun byDay(day: Long): Diary?

    @Query("SELECT * FROM diary WHERE uid = :uid")
    suspend fun byUid(uid: String): Diary?

    @Query("SELECT * FROM diary WHERE dirty = 1")
    suspend fun dirtyList(): List<Diary>

    @Query("DELETE FROM diary WHERE uid = :uid")
    suspend fun hardDeleteByUid(uid: String)

    @Query("UPDATE diary SET dirty = 1")
    suspend fun markAllDirty()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: Diary)

    @Update
    suspend fun update(d: Diary)
}

@Dao
interface StampDao {
    @Query("SELECT * FROM stamp WHERE deleted = 0")
    fun all(): Flow<List<Stamp>>

    /** 日程被删时解除印章绑定（B8：清理孤儿 eventUid） */
    @Query("UPDATE stamp SET eventUid = '', dirty = 1, updatedAt = :now WHERE eventUid = :eventUid AND deleted = 0")
    suspend fun unbindEvent(eventUid: String, now: Long)

    // §72 §5.2：Decorative → Event-bound（在快速创建页保存后回绑）
    @Query("UPDATE stamp SET eventUid = :eventUid, dirty = 1, updatedAt = :now WHERE id = :id")
    suspend fun bindEvent(id: Long, eventUid: String, now: Long)

    @Query("SELECT * FROM stamp WHERE deleted = 0")
    suspend fun listAll(): List<Stamp>

    @Query("SELECT * FROM stamp WHERE id = :id")
    suspend fun byId(id: Long): Stamp?

    @Query("SELECT * FROM stamp WHERE uid = :uid")
    suspend fun byUid(uid: String): Stamp?

    @Query("SELECT * FROM stamp WHERE dirty = 1")
    suspend fun dirtyList(): List<Stamp>

    @Query("DELETE FROM stamp WHERE uid = :uid")
    suspend fun hardDeleteByUid(uid: String)

    @Query("UPDATE stamp SET dirty = 1")
    suspend fun markAllDirty()

    @Insert
    suspend fun insert(s: Stamp): Long

    @Update
    suspend fun update(s: Stamp)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM template ORDER BY createdAt DESC")
    fun all(): Flow<List<Template>>

    @Insert
    suspend fun insert(t: Template)

    // CAL-062（§70）：独立模板编辑页需要改已有模板，不再只能删了重建
    @Query("UPDATE template SET title = :title, payload = :payload WHERE id = :id")
    suspend fun update(id: Long, title: String, payload: String)

    @Query("DELETE FROM template WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflict_log ORDER BY occurredAt DESC LIMIT 200")
    fun all(): Flow<List<ConflictLog>>

    @Insert
    suspend fun insert(c: ConflictLog)

    @Query("DELETE FROM conflict_log")
    suspend fun clear()

    @Query("DELETE FROM conflict_log WHERE id = :id")
    suspend fun delete(id: Long)
}


// ── §117 A：附件 ──
@Dao
interface AttachmentDao {
    @Query("SELECT * FROM Attachment WHERE ownerType = :type AND ownerUid = :owner AND deleted = 0 ORDER BY id")
    fun byOwner(type: String, owner: String): kotlinx.coroutines.flow.Flow<List<Attachment>>

    @Query("SELECT * FROM Attachment WHERE ownerType = :type AND ownerUid = :owner AND deleted = 0 ORDER BY id")
    suspend fun byOwnerOnce(type: String, owner: String): List<Attachment>

    @Query("SELECT * FROM Attachment WHERE uid = :uid LIMIT 1")
    suspend fun byUid(uid: String): Attachment?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: Attachment): Long

    @Update
    suspend fun update(a: Attachment)

    @Query("SELECT * FROM Attachment WHERE dirty = 1")
    suspend fun listDirty(): List<Attachment>

    @Query("SELECT * FROM Attachment WHERE remote = 0 AND deleted = 0 AND fileName != ''")
    suspend fun listNeedUpload(): List<Attachment>

    @Query("UPDATE Attachment SET dirty = 0 WHERE uid = :uid")
    suspend fun clearDirty(uid: String)

    @Query("SELECT COUNT(*) FROM Attachment WHERE ownerType = :type AND ownerUid = :owner AND deleted = 0")
    suspend fun countOf(type: String, owner: String): Int
}

// ── §126 B：聊天记录（本地持久、不上云，AI-UX §5）──
@Dao
interface ChatMessageDao {
    /** 最近一页（倒序取，UI 端 reversed() 还原时间序） */
    @Query("SELECT * FROM ChatMessage ORDER BY id DESC LIMIT :n")
    suspend fun latest(n: Int): List<ChatMessage>

    /** 翻页：比 beforeId 更早的一页 */
    @Query("SELECT * FROM ChatMessage WHERE id < :beforeId ORDER BY id DESC LIMIT :n")
    suspend fun before(beforeId: Long, n: Int): List<ChatMessage>

    @Insert
    suspend fun insert(m: ChatMessage): Long

    @Query("DELETE FROM ChatMessage WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM ChatMessage")
    suspend fun count(): Int

    /** 滚动清理要删的行（>30 天或超出最新 500 之外），先取出来删图片文件再删行 */
    @Query(
        "SELECT * FROM ChatMessage WHERE createdAt < :cutoff OR id NOT IN " +
            "(SELECT id FROM ChatMessage ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun listStale(cutoff: Long, keep: Int): List<ChatMessage>

    @Query(
        "DELETE FROM ChatMessage WHERE createdAt < :cutoff OR id NOT IN " +
            "(SELECT id FROM ChatMessage ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun purgeStale(cutoff: Long, keep: Int)

    @Query("DELETE FROM ChatMessage")
    suspend fun clearAll()
}

@Dao
interface AgentProposalDao {
    @Insert
    suspend fun insert(p: AgentProposal): Long

    /** 恢复：该 kind 最新一条未过期的 pending（actions 卡与 theme 卡各一张，互不顶替） */
    @Query("SELECT * FROM AgentProposal WHERE status='pending' AND kind=:kind AND expiresAt > :now ORDER BY id DESC LIMIT 1")
    suspend fun latestPending(kind: String, now: Long): AgentProposal?

    /** 状态跃迁守卫：只动 pending 行 —— 返回 0 = 已被处理过，调用方不得重复执行副作用 */
    @Query("UPDATE AgentProposal SET status=:st, resolvedAt=:now WHERE id=:id AND status='pending'")
    suspend fun resolve(id: Long, st: String, now: Long): Int

    /** 新提案顶掉同类旧 pending（一类只留一张活卡） */
    @Query("UPDATE AgentProposal SET status='dismissed', resolvedAt=:now WHERE status='pending' AND kind=:kind")
    suspend fun dismissPendingOfKind(kind: String, now: Long)

    @Query("UPDATE AgentProposal SET status='dismissed', resolvedAt=:now WHERE status='pending'")
    suspend fun dismissAllPending(now: Long)

    @Query("UPDATE AgentProposal SET status='expired', resolvedAt=:now WHERE status='pending' AND expiresAt <= :now")
    suspend fun expireOld(now: Long)

    /** 已了结行留 30 天可查（与聊天保留期一致），之后物理删 */
    @Query("DELETE FROM AgentProposal WHERE status != 'pending' AND resolvedAt < :cutoff")
    suspend fun purgeResolved(cutoff: Long)
}

/** §132 A1/A3：Agent 操作账本 —— 结构化审计 + 持久撤销的数据口 */
@Dao
interface AgentOperationDao {
    @Insert
    suspend fun insert(op: AgentOperation): Long

    /** 操作记录页：最近 N 条（页面按 batchId 分组展示） */
    @Query("SELECT * FROM AgentOperation ORDER BY id DESC LIMIT :n")
    suspend fun recent(n: Int): List<AgentOperation>

    /** 可撤批 = 最后一个含 succeeded 行的批（更早批只读展示，撤销安全模型与 §48 一致） */
    @Query("SELECT MAX(batchId) FROM AgentOperation WHERE result = 'succeeded' AND undoSnapshot != ''")
    suspend fun lastUndoableBatch(): Long?

    /** 撤销回放取批内成功行；倒序 = 逆序回放（后做的先撤） */
    @Query("SELECT * FROM AgentOperation WHERE batchId = :b AND result = 'succeeded' AND undoSnapshot != '' ORDER BY id DESC")
    suspend fun undoableOfBatch(b: Long): List<AgentOperation>

    /** 状态跃迁守卫与提案同哲学：只动 succeeded 行，双击撤销幂等 */
    @Query("UPDATE AgentOperation SET result = 'undone' WHERE id = :id AND result = 'succeeded'")
    suspend fun markUndone(id: Long): Int

    /** 审计保留 90 天（本机过程数据，不进同步） */
    @Query("DELETE FROM AgentOperation WHERE createdAt < :cutoff")
    suspend fun purgeOld(cutoff: Long)
}
