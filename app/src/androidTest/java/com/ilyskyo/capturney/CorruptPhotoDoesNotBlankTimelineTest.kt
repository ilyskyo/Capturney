// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ilyskyo.capturney.core.AppContainer
import com.ilyskyo.capturney.data.model.Entry
import com.ilyskyo.capturney.data.model.EntryObject
import com.ilyskyo.capturney.data.model.OverlayLayer
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * 一张**坏掉的照片**不许把整条时间轴带走。
 *
 * ## 这不是假想的场景
 *
 * 云备份只带 `deck.json` 与 `diary.json`，照片不上云（§8.4），所以换机之后**必然**出现
 * 「日记里有 photoPath，磁盘上没有那个文件」；而另一半更常见：文件在、内容不完整
 * （备份中断、下载被切、SD 卡上写坏一次 JPEG）。详情页早就为前者写了占位（`bitmap == null`
 * 时照常显示正文），但**时间轴是批量解码**的：一条 `combine` 里对 40 张逐个解码，
 * 其中任何一张抛异常，这一屏就整体没了——用户看到的是「我的日记全空了」。
 *
 * ## 这条测的是哪一段
 *
 * 一张好照片 + 一张坏照片（同一批、同一条解码路径）：两张卡片上的词都必须还在。
 * 词来自条目本身而不是位图，所以「坏的那张也照常报出自己的词」正是这件事成立的形状：
 * 照片没了是遗憾，记录没了是事故。
 */
@RunWith(AndroidJUnit4::class)
class CorruptPhotoDoesNotBlankTimelineTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(PreserveAppFiles())
        .around(SeedGoodAndBroken())
        .around(compose)

    /** 一条照片正常、一条照片是垃圾字节；两条各占一天，免得被分到同一组里互相掩盖。 */
    private class SeedGoodAndBroken : ExternalResource() {
        override fun before() {
            val container = app().container
            File(container.entryPhotoDir, GOOD_PHOTO).writeBytes(Base64.decode(TINY_PNG, Base64.DEFAULT))
            // 后缀是 .jpg、内容是文本：解码器拿到的就是「文件在但读不出位图」这一类，
            // 也正是备份中断/半张下载留在磁盘上的样子。
            File(container.entryPhotoDir, BROKEN_PHOTO).writeText("这不是图片，只是一段被留在磁盘上的字节")
            val now = System.currentTimeMillis()
            val day = TimeUnit.DAYS.toMillis(1)
            val diary = container.diary
            runBlocking {
                FIXTURE_IDS.forEach { diary.deleteEntry(it) }
                listOf(
                    entry("fixture-photo-good", "cup", GOOD_PHOTO, now),
                    entry("fixture-photo-broken", "bowl", BROKEN_PHOTO, now - day),
                ).forEach { card ->
                    assertTrue("夹具没种进去：${card.id}", diary.addEntry(card))
                }
            }
        }

        override fun after() {
            val container = app().container
            runBlocking { FIXTURE_IDS.forEach { container.diary.deleteEntry(it) } }
            // 照片文件由 PreserveAppFiles 兜底还原，这里只保证不留下垃圾命名。
            File(container.entryPhotoDir, BROKEN_PHOTO).delete()
        }

        private fun entry(id: String, word: String, photo: String, takenAt: Long) = Entry(
            id = id,
            photoPath = photo,
            takenAt = takenAt,
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
    }

    @Test
    fun the_good_card_renders_and_the_broken_one_still_reports_its_words() {
        // 一次 waitForIdle 不够：lookback 是从 Dispatchers.Default 发流的，图片还在异步解码，
        // 首帧完全可能还没有任何卡片。以前只 waitForIdle() 然后直接 assertIsDisplayed，
        // 于是这条测试**间歇性红**（同一份代码连跑三次两次绿一次红），而红的时候界面是对的。
        // 改成等到那个词真的在树里再断言它「显示着」——等待负责时序，断言负责语义。
        awaitWord("cup")
        compose.onNodeWithText("cup").assertIsDisplayed()
        // 这一条才是重点：照片读不出来，记录本身必须照常出现。
        awaitWord("bowl")
        compose.onNodeWithText("bowl").assertIsDisplayed()
    }

    /** 等到写着 [word] 的节点出现；超时交给 `waitUntil` 自己报错，不在这里替它判断。 */
    private fun awaitWord(word: String) {
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(word)).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val GOOD_PHOTO = "fixture-good.png"
        const val BROKEN_PHOTO = "fixture-broken.jpg"

        /** 比默认 1000ms 宽一档：这台 AVD 的冷启动首帧能到几百毫秒。 */
        const val TIMEOUT_MS = 5_000L

        val FIXTURE_IDS = listOf("fixture-photo-good", "fixture-photo-broken")

        const val TINY_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

        fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as CapturneyApplication
    }
}
