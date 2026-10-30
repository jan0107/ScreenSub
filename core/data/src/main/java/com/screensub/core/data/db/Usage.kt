// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query

/**
 * 按「引擎 × 自然日」聚合的用量。
 *
 * 为什么按日而不是按月一行：要实现「最近 7 日消耗」就必须有日粒度，
 * 而月度累计可以由同月若干行求和得到。反过来（只存月度）就再也拆不出日曲线。
 *
 * 为什么不用 DataStore：这里是"随每句翻译增长"的时序数据，
 * 每次写都要全量序列化整个结构，写放大严重；而 Room 的 upsert 是原子增量更新。
 *
 * [dayKey] 用本地时区的 `yyyy-MM-dd`。用户感知的"今天用了多少"是本地日期，
 * 而月度重置按厂商的自然月——两者在月初/月末的时区边界上会差几小时，
 * 这个误差（最多几十字符）远小于我们从账单校准的精度，可接受。
 */
@Entity(tableName = "usage_daily", primaryKeys = ["engineId", "dayKey"])
data class UsageDailyEntity(
    val engineId: String,
    val dayKey: String,
    /** 计费字符数（LLM 不计字符，这里恒为 0） */
    val chars: Long = 0,
    /** 请求次数，含失败的 */
    val requests: Long = 0,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    /** 金额，单位「微元」（1 元 = 1_000_000），避免浮点累加误差 */
    val costMicroYuan: Long = 0,
    val updatedAt: Long = 0,
)

@Dao
interface UsageDao {

    @Query("SELECT * FROM usage_daily WHERE dayKey >= :sinceKey ORDER BY dayKey ASC")
    suspend fun since(sinceKey: String): List<UsageDailyEntity>

    @Query("SELECT * FROM usage_daily WHERE dayKey LIKE :monthPrefix || '-%'")
    suspend fun month(monthPrefix: String): List<UsageDailyEntity>

    @Query("SELECT * FROM usage_daily ORDER BY dayKey ASC")
    suspend fun all(): List<UsageDailyEntity>

    /**
     * 原子累加。
     *
     * 用 upsert 而不是「先查再写」：翻译是 2 路并发（MT_CONCURRENCY = 2），
     * 先查后写必然丢更新。SQLite 3.24+ 支持 ON CONFLICT，minSdk 31 的内置版本远高于此。
     */
    @Query(
        """
        INSERT INTO usage_daily(engineId, dayKey, chars, requests, tokensIn, tokensOut, costMicroYuan, updatedAt)
        VALUES(:engineId, :dayKey, :chars, :requests, :tokensIn, :tokensOut, :costMicro, :now)
        ON CONFLICT(engineId, dayKey) DO UPDATE SET
            chars = chars + excluded.chars,
            requests = requests + excluded.requests,
            tokensIn = tokensIn + excluded.tokensIn,
            tokensOut = tokensOut + excluded.tokensOut,
            costMicroYuan = costMicroYuan + excluded.costMicroYuan,
            updatedAt = excluded.updatedAt
        """,
    )
    suspend fun accumulate(
        engineId: String,
        dayKey: String,
        chars: Long,
        requests: Long,
        tokensIn: Long,
        tokensOut: Long,
        costMicro: Long,
        now: Long,
    )

    @Query("DELETE FROM usage_daily WHERE dayKey < :cutoffKey")
    suspend fun purgeBefore(cutoffKey: String)

    @Query("DELETE FROM usage_daily")
    suspend fun clear()
}
