package com.looka.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Category::class, EventSeries::class, EventException::class, Reminder::class,
        TaskList::class, Task::class, Note::class, Diary::class, Stamp::class,
        Template::class, ConflictLog::class
    ],
    version = 6,
    exportSchema = false
)
abstract class LookaDb : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun eventDao(): EventDao
    abstract fun taskListDao(): TaskListDao
    abstract fun taskDao(): TaskDao
    abstract fun noteDao(): NoteDao
    abstract fun diaryDao(): DiaryDao
    abstract fun stampDao(): StampDao
    abstract fun templateDao(): TemplateDao
    abstract fun conflictDao(): ConflictDao

    companion object {
        /**
         * v3 → v4 正规迁移（B13：告别破坏式迁移，用户数据从此保得住）：
         * task.sortOrder / stamp.assetId / conflict_log 表 / 各表查询索引（S6）
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE task SET sortOrder = id")
                db.execSQL("ALTER TABLE stamp ADD COLUMN assetId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conflict_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, kind TEXT NOT NULL, " +
                        "title TEXT NOT NULL, payload TEXT NOT NULL, occurredAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_category_uid ON category(uid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_event_series_uid ON event_series(uid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_list_uid ON task_list(uid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_uid ON task(uid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_listUid ON task(listUid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_dueDay ON task(dueDay)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_uid ON note(uid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_diary_uid ON diary(uid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stamp_uid ON stamp(uid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stamp_day ON stamp(day)")
            }
        }

        /** v4 → v5（A2 真闹钟）：reminder.alarm —— 「当成闹钟」标记 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminder ADD COLUMN alarm INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5 → v6（Sticker Canvas v1，§68）：印章格内相对坐标，-1 = 未摆放 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stamp ADD COLUMN posX REAL NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE stamp ADD COLUMN posY REAL NOT NULL DEFAULT -1")
            }
        }
    }
}
