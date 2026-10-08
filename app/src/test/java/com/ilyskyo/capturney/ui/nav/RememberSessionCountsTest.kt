// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney.ui.nav

import com.ilyskyo.capturney.data.model.EventCard
import com.ilyskyo.capturney.data.model.FsrsState
import com.ilyskyo.capturney.data.model.ReviewItem
import com.ilyskyo.capturney.data.model.StudyDirection
import com.ilyskyo.capturney.data.model.WordCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本轮」那两个读数的算法。
 *
 * 放在 JVM 而不是设备测试里，是因为它们坏起来的样子的界面**看不出来**：进度条多算一张、
 * 「已完成 2 / 共 8」里那 8 张中有一张其实已经被答过了——数字仍然自洽，只是不等于事实。
 * 而重学步长（按「忘了」的卡留在这一轮里）之前，这条分支根本走不到，所以它是新近才真正
 * 需要成立的一件事。
 */
class RememberSessionCountsTest {

    @Test
    fun aRequeuedCardIsNotCountedTwice() {
        // cup 刚评了「忘了」：它答过了，也还在队里。
        val (done, total) = sessionCounts(setOf("w:cup"), listOf("w:cup", "w:mug", "w:bowl"))
        assertEquals(1, done)
        assertEquals(3, total)
    }

    @Test
    fun theRoundDoesNotGrowWhileTheSameCardIsAnsweredAgain() {
        // 连着两次评同一张（先「忘了」再「好」）：done 按张去重，所以第二轮不会把分母推大。
        val first = sessionCounts(setOf("w:cup"), listOf("w:cup", "w:mug"))
        val second = sessionCounts(setOf("w:cup"), listOf("w:mug"))
        assertEquals("同一张卡不该被数两次", 2, first.second)
        assertEquals(1, second.first)
        assertEquals(2, second.second)
    }

    @Test
    fun donePlusPendingAlwaysEqualsTotal() {
        // 这是进度条不往回退的那句不变式：不管队里剩什么，done 加未答过的张数就是总数。
        for (attempted in listOf(emptySet(), setOf("w:a"), setOf("w:a", "w:b"))) {
            for (queue in listOf(emptyList(), listOf("w:a"), listOf("w:a", "w:b"), listOf("w:b", "w:c"))) {
                val (done, total) = sessionCounts(attempted, queue)
                val pending = queue.count { it !in attempted }
                assertEquals(total.toLong(), (done + pending).toLong())
                assertTrue("total 不该小于 done", total >= done)
            }
        }
    }

    @Test
    fun wordAndEventWithTheSameIdStayTwoDifferentCards() {
        val keys = listOf(
            reviewKey(ReviewItem.Word(word("x"))),
            reviewKey(ReviewItem.Event(event("x"))),
        )
        assertEquals(listOf("w:x", "e:x"), keys)
    }

    @Test
    fun theCardDueSoonestIsPresentedFirstAndARelearnCardGoesLast() {
        val due = word("due").copy(states = state(due = 1_000L))
        val fresh = word("fresh")
        val relearning = word("relearning").copy(states = state(due = 60_000L))
        val queue = listOf(relearning, due, fresh).map { ReviewItem.Word(it) }
        val ordered = queue.sortedBy { it.dueKey(StudyDirection.RECOGNIZE) }.map { reviewKey(it) }
        // 新卡没有状态，排最前（先介绍新词）；一分钟之后才到期的那张落到队尾。
        assertEquals(listOf("w:fresh", "w:due", "w:relearning"), ordered)
    }

    @Test
    fun anEventCardReadsItsOwnScheduleNotTheWordS() {
        val item: ReviewItem = ReviewItem.Event(event("e1").copy(states = state(due = 42L)))
        assertEquals(42L, item.dueKey(StudyDirection.RECALL))
        assertEquals("e:e1", reviewKey(item))
    }

    private fun state(due: Long) = mapOf(StudyDirection.RECOGNIZE.name to FsrsState(due = due, n = 3))

    private fun word(id: String) =
        WordCard(id = id, headword = id, language = "en", glosses = mapOf("zh" to "释义"))

    private fun event(id: String) = EventCard(id = id, text = "一句话", happenedAt = 0L)
}
