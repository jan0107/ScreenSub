// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 一条字幕记录。时间戳来自 VAD 样本偏移，不是系统时间。 */
@Entity(tableName = "cues")
data class CueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startMs: Long,
    val endMs: Long,
    val sourceText: String,
    val translatedText: String,
    val isFinal: Boolean,
)

/** 术语表。source 可以是字面文本，也可以是正则。 */
@Entity(tableName = "glossary")
data class GlossaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val target: String,
    val priority: Int = 0,
    val isRegex: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 一次字幕会话，用于把 cue 分组和统计 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val sourceLanguage: String,
    val modelId: String,
    val audioSource: String,
)

@Dao
interface CueDao {

    @Insert
    suspend fun insert(cue: CueEntity): Long

    @Query("SELECT * FROM cues WHERE sessionId = :sessionId ORDER BY startMs ASC")
    suspend fun bySession(sessionId: Long): List<CueEntity>

    @Query("SELECT * FROM cues WHERE sessionId = :sessionId AND isFinal = 1 ORDER BY startMs ASC")
    suspend fun finalCuesBySession(sessionId: Long): List<CueEntity>

    @Query("DELETE FROM cues WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: Long)

    @Query("SELECT COUNT(*) FROM cues WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: Long): Int
}

@Dao
interface GlossaryDao {

    @Insert
    suspend fun insert(term: GlossaryEntity): Long

    @Query("SELECT * FROM glossary ORDER BY priority DESC, LENGTH(source) DESC")
    suspend fun all(): List<GlossaryEntity>

    @Query("DELETE FROM glossary WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM glossary")
    suspend fun clear()
}

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 50")
    suspend fun recent(): List<SessionEntity>
}

@Database(
    entities = [
        CueEntity::class,
        GlossaryEntity::class,
        SessionEntity::class,
        UsageDailyEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class ScreenSubDatabase : RoomDatabase() {
    abstract fun cueDao(): CueDao
    abstract fun glossaryDao(): GlossaryDao
    abstract fun sessionDao(): SessionDao
    abstract fun usageDao(): UsageDao

    companion object {
        const val NAME = "screensub.db"

        /**
         * v1 → v2：只新增 `usage_daily` 表。
         *
         * **必须显式提供**：`AppContainer` 用了 `fallbackToDestructiveMigration()`，
         * 一旦找不到匹配的 Migration 就会把整库删掉重建——用户的字幕历史和术语表全没。
         * 有了这个 Migration，升级只是多一张空表，旧数据原样保留。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_daily` (
                        `engineId` TEXT NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `chars` INTEGER NOT NULL DEFAULT 0,
                        `requests` INTEGER NOT NULL DEFAULT 0,
                        `tokensIn` INTEGER NOT NULL DEFAULT 0,
                        `tokensOut` INTEGER NOT NULL DEFAULT 0,
                        `costMicroYuan` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`engineId`, `dayKey`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
