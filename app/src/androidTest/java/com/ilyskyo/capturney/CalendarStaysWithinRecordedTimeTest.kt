// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import android.app.Application
import android.util.Base64
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ilyskyo.capturney.core.AppContainer
import com.ilyskyo.capturney.data.model.Entry
import com.ilyskyo.capturney.data.model.EntryObject
import com.ilyskyo.capturney.data.model.OverlayLayer
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/**
 * 月历不许翻进未来（#34）的永久守卫。
 *
 * 这个 bug 当初是**看图**看见的：右箭头一直可点，翻进还没有记录的那个月，得到一张
 * 全是点不开格子的空页——而 300 多项单测与当时那批设备测试全都绿着。
 *
 * ## 断的是「禁用」而不是「不存在」
 *
 * 实现走 `IconButton(enabled = month < YearMonth.from(today))`：箭头一直在，只是当月不可用。
 * 写成 `assertDoesNotExist()` 会得到一条假绿的守卫——把 `enabled` 删掉它也照样通过，
 * 而那时用户看到的正是一个能翻进未来的箭头。
 */
@RunWith(AndroidJUnit4::class)
class CalendarStaysWithinRecordedTimeTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    /**
     * 必须套 `PreserveAppFiles`：这条测试往日记里真写两条记录，而它按字母序跑在前面。
     * 上一版没套，全量跑时把后面的 `RememberSessionTest` 弄红了（单类跑却是绿的）。
     */
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(PreserveAppFiles()).around(compose)

    @Before
    fun seedTwoDays() {
        val container = container()
        writePhoto(container, PHOTO_TODAY)
        writePhoto(container, PHOTO_PAST)
        runBlocking {
            val now = System.currentTimeMillis()
            val firstOfLastMonth = LocalDate.now().minusMonths(1).withDayOfMonth(1).atTime(12, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            assertTrue("今天那条没种进去", container.diary.addEntry(entry(TODAY_ID, PHOTO_TODAY, "mug", now)))
            assertTrue("上个月那条没种进去", container.diary.addEntry(entry(PAST_ID, PHOTO_PAST, "lantern", firstOfLastMonth)))
        }
    }

    @After
    fun removeSeededDays() {
        // 内存里那份文档会活到下一个类（PreserveAppFiles 只还原磁盘），所以必须走应用自己的删除路径。
        val container = container()
        runBlocking {
            container.diary.deleteEntry(TODAY_ID)
            container.diary.deleteEntry(PAST_ID)
        }
    }

    @Test
    fun theNextMonthArrowIsDeadOnTheCurrentMonthAndWakesUpOneMonthBack() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.calendar_title)).performClick()
        val next = compose.activity.getString(R.string.calendar_next)

        // 刚点开的是 ModalBottomSheet：滑入动画 + 月历格子异步组合，点完立刻断言就是在赌时序。
        // 这条测试自己跑绿过三遍，但它和刚修掉的那条竞态是同一个形状——先等到节点进了树再断言状态。
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasContentDescription(next)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription(next)
            .assert(isNotEnabled()) { "当月还能按「下一月」：这就是 #34 那张点不开的空白页" }

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.calendar_prev))
            .assert(isEnabled())
            .performClick()

        compose.onNodeWithContentDescription(next)
            .assert(isEnabled()) { "退回上个月之后「下一月」还是死的：那是把回得来这条路也堵掉了" }
    }

    private fun container(): AppContainer =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            as CapturneyApplication).container

    private fun entry(id: String, photo: String, word: String, at: Long) = Entry(
        id = id,
        photoPath = photo,
        takenAt = at,
        kind = "kitchen",
        kindLabel = mapOf("en" to "kitchen", "zh" to "厨房"),
        ambience = listOf("warm"),
        objects = listOf(
            EntryObject(
                id = word,
                word = word,
                score = 0.9f,
                left = 0.2f,
                top = 0.3f,
                right = 0.5f,
                bottom = 0.8f,
                layer = OverlayLayer.ITEM,
            ),
        ),
    )

    private fun writePhoto(container: AppContainer, name: String) {
        File(container.entryPhotoDir, name).writeBytes(Base64.decode(TINY_PNG, Base64.DEFAULT))
    }

    private companion object {
        const val TODAY_ID = "calendar-guard-today"
        const val PAST_ID = "calendar-guard-past"
        const val PHOTO_TODAY = "calendar-guard-today.png"
        const val PHOTO_PAST = "calendar-guard-past.png"

        /** 与 CorruptPhoto 那条同档：这台 AVD 的冷启动首帧能到几百毫秒。 */
        const val TIMEOUT_MS = 5_000L

        const val TINY_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
    }
}
