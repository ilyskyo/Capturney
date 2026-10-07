// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import android.app.Application
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ilyskyo.capturney.core.AppContainer
import com.ilyskyo.capturney.data.model.Entry
import com.ilyskyo.capturney.data.model.EntryObject
import com.ilyskyo.capturney.data.model.OverlayLayer
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * 长按顶部页签的「随机漫步」（#15）此前一条设备测试都没有。
 *
 * ## 三条承诺，重点在第三条
 *
 * 有可去的过去 → 真的跳进那一天的详情页；只有今天 → 给一句「还没什么可回去的」，
 * 而不是页签震了一下什么都没发生。**第三条才是这份测试存在的主要理由**：
 * 只有一个过去的日子时，第二次按必须仍然去得了那一天。排除「最近去过的那几天」是为了
 * 不连着重复，不是「那几天从此去不了」——一个用户真的只有 20 月 3 号那一天，
 * 对他说「你的日记还太空」是**撒谎**，而这句话读起来完全合理，所以没人会去查它。
 */
@RunWith(AndroidJUnit4::class)
class RandomWalkOpensAPastDayOrSaysSo {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(PreserveAppFiles()).around(compose)

    private val seeded = mutableListOf<String>()

    @Before
    fun startFromAnEmptyDiary() {
        // 「只有今天」和「只有一个过去的日子」这两条断言的前提都是**别的日子确实没有记录**。
        // 不先清场，前一次跑挂留下的残留就会让它变成条件断言：池子非空 → 真的跳走了 →
        // 「该给一句提示」那条红，而「第二个日子」那条恰好因为多出一个可去的日期而假绿。
        // PreserveAppFiles 只把磁盘还原到快照，快照本身可能就带着垃圾，所以清内存这一层得自己做。
        val container = container()
        runBlocking {
            container.diary.document.value.entries.map { it.id }.forEach { container.diary.deleteEntry(it) }
        }
    }

    @After
    fun clearSeeded() {
        val container = container()
        runBlocking { seeded.forEach { container.diary.deleteEntry(it) } }
        seeded.clear()
    }

    @Test
    fun walkingBackOpensThatDaysDetailPage() {
        seed("walk-past", "compass", daysAgo = 3)

        longPressLookbackTab()
        awaitDetailPage()

        compose.onNodeWithContentDescription(str(R.string.detail_close)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.walk_empty)).assertDoesNotExist()
    }

    @Test
    fun aDiaryWithOnlyTodayEntrySaysSoInsteadOfSilentlyDoingNothing() {
        seed("walk-today-only", "kite", daysAgo = 0)

        longPressLookbackTab()
        awaitNotice()

        // 关键不是「有没有跳」，而是**有没有一句话**：静默无反应读起来像按钮坏了。
        compose.onNodeWithText(str(R.string.walk_empty)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.detail_close)).assertDoesNotExist()
    }

    @Test
    fun theSinglePastDayIsStillWalkableTheSecondTime() {
        seed("walk-one-day", "lantern", daysAgo = 5)

        longPressLookbackTab()
        awaitDetailPage()
        backToHome()

        longPressLookbackTab()
        awaitDetailPage()

        compose.onNodeWithText(str(R.string.walk_empty)).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.detail_close)).assertIsDisplayed()
    }

    private fun longPressLookbackTab() {
        compose.onNodeWithContentDescription(str(R.string.home_tab_lookback)).performTouchInput { longClick() }
    }

    private fun backToHome() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun awaitDetailPage() {
        val close = str(R.string.detail_close)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasContentDescription(close)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitNotice() {
        val empty = str(R.string.walk_empty)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText(empty)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun seed(id: String, word: String, daysAgo: Int) {
        val container = container()
        val photo = "$id.jpg"
        File(container.entryPhotoDir, photo).writeBytes(Base64.decode(TINY_PNG, Base64.DEFAULT))
        val at = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysAgo.toLong())
        runBlocking {
            val added = container.diary.addEntry(
                Entry(
                    id = id,
                    photoPath = photo,
                    takenAt = at,
                    kind = "street",
                    kindLabel = mapOf("en" to "street", "zh" to "街道"),
                    ambience = listOf("quiet"),
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
                ),
            )
            assertTrue("夹具没种进去：$id", added)
        }
        seeded += id
    }

    private fun str(res: Int) = compose.activity.getString(res)

    private fun container(): AppContainer =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            as CapturneyApplication).container

    private companion object {
        const val TIMEOUT_MS = 5_000L

        const val TINY_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
    }
}
