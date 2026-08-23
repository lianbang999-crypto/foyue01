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
interface NoteDao {
    @Query("SELECT * FROM note WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun all(): Flow<List<Note>>

    @Query("SELECT * FROM note WHERE deleted = 0")
    suspend fun listAll(): List<Note>

    @Query("SELECT * FROM note WHERE id = :id AND deleted = 0")
    suspend fun byId(id: Long): Note?

    @Query("SELECT * FROM note WHERE uid = :uid")
    suspend fun byUid(uid: String): Note?

    @Query("SELECT * FROM note WHERE dirty = 1")
    suspend fun dirtyList(): List<Note>

    @Query("DELETE FROM note WHERE uid = :uid")
    suspend fun hardDeleteByUid(uid: String)

    @Query("UPDATE note SET dirty = 1")
    suspend fun markAllDirty()

    @Insert
    suspend fun insert(n: Note): Long

    @Update
    suspend fun update(n: Note)
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
    suspend fun insert(s: Stamp)

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
