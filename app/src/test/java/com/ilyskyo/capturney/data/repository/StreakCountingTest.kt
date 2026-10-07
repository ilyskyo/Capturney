// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney.data.repository

import com.ilyskyo.capturney.data.model.ReviewLog
import com.ilyskyo.capturney.data.model.StudyDirection
import com.ilyskyo.capturney.data.store.JsonDocument
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 连续天数（streak）是首页与小组件上唯一一个「算错了也长得像对的」数字：
 * 少算一天用户只会默默少一点动力，多算一天则是在骗人。它此前**一条测试都没有**。
 *
 * ## 为什么要连着时区一起测
 *
 * 回退用的是 `LocalDate.minusDays(1)`，而**分桶用的是设备本地时区**——这两处必须一致。
 * 一旦有一侧被换成 UTC（最常见的是「统一都存 UTC 吧」这类改动），跨 UTC 午夜的那几次评分
 * 就会被归到隔壁那一天去：`America/New_York` 的 10-31 22:00 在 UTC 已经是 11-01 03:00，
 * 于是连续两天在数字上变成「同一天评了两次」，streak 从 2 掉到 1，而界面上一切正常。
 * 下面那两条夏令时用例挑的就是这种日子：2026-10-31 22:00 与 2026-11-01 23:00 正好一个跨
 * UTC 午夜、一个不跨，两侧不一致时它们给出的答案必然分叉。
 *
 * 顺带一条与直觉相反的：夏令时结束那天有 25 小时，但**往回**减 24 小时并不会跳过一天
 * （会跳过的是往前减的那一侧），所以这两条用例钉的是「分桶时区一致」，不是「按日历天步进」。
 */
class StreakCountingTest {

    private val zone = ZoneId.of("America/New_York")
    private val originalZone = TimeZone.getDefault()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun useNewYork() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreZone() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `a streak counts consecutive calendar days and survives an untouched today`() {
        assertEquals("一条记录都没有，连续天数应当是 0", 0, streakOf(now = day(2026, 6, 15, 9)))

        assertEquals(
            "今天、昨天、前天各评过一次，应当是 3",
            3,
            streakOf(now = day(2026, 6, 15, 9), at = listOf(day(2026, 6, 15, 8), day(2026, 6, 14, 20), day(2026, 6, 13, 7))),
        )

        assertEquals(
            "今天还没评，昨天与前天评过——注释承诺了这一格：应当从昨天起算，是 2",
            2,
            streakOf(now = day(2026, 6, 15, 9), at = listOf(day(2026, 6, 14, 20), day(2026, 6, 13, 7))),
        )

        assertEquals(
            "昨天那一天是空的，连续就已经断了：不该一路回退到前天",
            0,
            streakOf(now = day(2026, 6, 15, 9), at = listOf(day(2026, 6, 13, 7))),
        )

        assertEquals(
            "同一天里评了五张卡，仍然只算一天",
            1,
            streakOf(now = day(2026, 6, 15, 9), at = (1..5).map { day(2026, 6, 15, it) }),
        )
    }

    @Test
    fun `day bucketing and the day-by-day walk agree on which local day a review belongs to`() {
        // 2026-10-31 22:00 在 UTC 已经是 11-01：分桶若换成 UTC，这两天会被并成一天。
        val oct31 = day(2026, 10, 31, 22)
        val nov1 = day(2026, 11, 1, 23)
        assertEquals(
            "跨 UTC 午夜的那次评分被归到了隔壁一天：分桶与回退两侧用的时区不一致",
            2,
            streakOf(now = nov1, at = listOf(oct31, nov1)),
        )

        // 2026-03-08 00:30 在 UTC 仍是 03-08：这一条不该被同一处改动误伤，所以两侧都要对。
        assertEquals(
            "不跨 UTC 午夜的一对日子也算错了：那说明断掉的不是时区，而是逐日回退本身",
            2,
            streakOf(now = day(2026, 3, 8, 0, 30), at = listOf(day(2026, 3, 7, 23, 30), day(2026, 3, 8, 0, 30))),
        )
    }

    /** 只读 `streak`，所以磁盘上那个文件根本不必存在：`JsonDocument` 在读到之前给的就是 fallback。 */
    private fun streakOf(now: Long, at: List<Long> = emptyList()): Int {
        val document = JsonDocument(
            file = File.createTempFile("streak", ".json").apply { delete() },
            fallback = { DeckDocument(log = at.map { ReviewLog(cardId = "c", direction = com.ilyskyo.capturney.data.model.StudyDirection.RECOGNIZE, rating = 3, at = it) }) },
            serializer = DeckDocument.serializer(),
            scope = scope,
        )
        return DeckRepository(document, scope).streak(now)
    }

    private fun day(year: Int, month: Int, dayOfMonth: Int, hour: Int, minute: Int = 0): Long =
        LocalDate.of(year, month, dayOfMonth).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
