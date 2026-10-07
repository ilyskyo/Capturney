// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import android.app.Application
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ilyskyo.capturney.R
import com.ilyskyo.capturney.core.AppContainer
import com.ilyskyo.capturney.data.model.Entry
import com.ilyskyo.capturney.data.model.EntryObject
import com.ilyskyo.capturney.data.model.OverlayLayer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * 时间轴上的卡片要**自己报出这是哪一天的哪个词**。
 *
 * ## 为什么这条要跑在设备上、还要先喂数据
 *
 * 其余三条语义扫描跑的是空时间轴——回看页只扫到 4 个可点节点，卡片那一整层根本没参与。
 * 而卡片是这款 App 被看得最多的东西：贴纸、日期、词、氛围词、多选勾选框叠在一起，
 * 每一样都可能把读屏的朗读顺序搅乱。JVM 侧看不到布局，`lintVitalRelease` 也看不到。
 *
 * 夹具走的是**应用自己的写入路径**（`container.diary.addEntry`），不是手搓 JSON：
 * 手写会把「字段名变了但测试还在过」这种假象留下来，而走真实路径时 schema 一变测试就跟着醒。
 * 种在 `PreserveAppFiles` 的内层，所以跑完连同夹具一起被清掉——
 * 别人的设备上不会留下这条假记录。
 */
@RunWith(AndroidJUnit4::class)
class TimelineSemanticsTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(PreserveAppFiles())
        .around(SeedDiary())
        .around(compose)

    /** 一条真实形状的记录：一张照片 + 一个词 + 一句氛围词。 */
    private class SeedDiary : ExternalResource() {
        override fun before() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val container = (app as CapturneyApplication).container
            writePhoto(container, PHOTO_NAME)
            val added = runBlocking {
                container.diary.addEntry(
                    Entry(
                        id = ENTRY_ID,
                        photoPath = PHOTO_NAME,
                        takenAt = System.currentTimeMillis(),
                        kind = "kitchen",
                        kindLabel = mapOf("en" to "kitchen", "zh" to "厨房"),
                        ambience = listOf("warm"),
                        objects = listOf(
                            EntryObject(
                                id = "cup",
                                word = "cup",
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
            }
            // 同 id 只认第一次，所以这一步是在问「上一条夹具是不是还留着」。
            // 留着的话下面两条测的就不是这条记录（而且按文字找会撞上两个节点）。
            assertTrue("夹具没种进去：这份文档里已经有一条同 id 的记录，说明上一条没被清掉", added)
        }

        /**
         * 走应用自己的删除路径收掉夹具。
         *
         * 只在磁盘上做还原是不够的：同一个进程会连着跑完这一类的所有方法，而 `AppContainer`
         * 里那份 `JsonDocument` 是内存态——`PreserveAppFiles` 把 diary.json 换了回去，
         * 内存里那条记录还在。第一个方法（按字母序是 `tapping…`）种下的「cup」，
         * 到第二个方法时仍然在 `_state` 里，于是 `addEntry` 判重返回 false，
         * 而下面任何按文字找的断言都会撞上「找到 2 个节点」。
         *
         * 删这条同时清掉照片：`deleteEntry` 本来就要连带媒体走，正好和 `addEntry` 对称。
         */
        override fun after() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val container = (app as CapturneyApplication).container
            runBlocking { container.diary.deleteEntry(ENTRY_ID) }
        }

        private fun writePhoto(container: AppContainer, name: String) {
            // 1x1 的 PNG。条目要求 photoPath 真的存在（没有照片的日记不成立），
            // 而这里要验的是卡片怎么说话，不是像素。
            val bytes = Base64.decode(TINY_PNG, Base64.DEFAULT)
            File(container.entryPhotoDir, name).writeBytes(bytes)
        }
    }

    /**
     * 卡片必须是**一个**可点节点，并且这一个节点同时报得出「哪个词」和「哪一天」。
     *
     * 为什么不用「可点节点比空页面多」当断言：实测空时间轴 4 个、喂一条记录 6 个，
     * 差值只有 2，任何一颗新加的按钮都能把它推过去——那种断言只是在数数，不在说事。
     *
     * 这两条才是要紧的：
     *
     * - `hasClickAction() and hasText("cup")` 命中**恰好一个**节点，说明词被合并进了可点的那一节。
     *   词只长在未合并的子 Text 上时，读屏聚焦到卡片上念不出词，多选与点击的落点也跟着错位。
     *   0 个就是这种情况（也覆盖卡片整张没渲染），多于 1 个是上一条夹具没清掉。
     * - 卡片里的 contentDescription 目前只有照片那一句（哪一天拍的），贴纸与勾选框都是刻意留空的。
     *   它为空就意味着读屏用户点进去之前只知道「cup」，不知道那是哪一天——而整个时间轴上
     *   区分两条记录的只有日期。
     */
    @Test
    fun theSeededCardIsOnScreenAndClickable() {
        compose.waitForIdle()
        compose.onNodeWithText("cup").assertIsDisplayed()

        val cards = compose.onAllNodes(hasClickAction() and hasText("cup")).fetchSemanticsNodes()
        assertTrue(
            "「可点而且写着 cup」的节点有 ${cards.size} 个，应当恰好 1 个（0 个说明卡片没渲染，" +
                "多于 1 个说明上一条夹具没清掉）",
            cards.size == 1,
        )
        val descriptions = cards.single().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        assertTrue(
            "卡片报不出日期：可点节点里的 contentDescription 是空的，读屏念不到「哪一天」",
            descriptions.isNotEmpty(),
        )
    }

    /**
     * 卡片必须能被点进详情。
     *
     * 这一条存在的全部理由：`openEntry` 返回的栈曾经被调用点丢掉，于是点卡片没反应
     * ——编得过、单测全绿、只有真点一下才知道。
     *
     * 点的是「cup」那一节，`performClick()` 会解析成它的中心坐标再注入——落点就在卡片之内，
     * 与手指的位置一致。而「卡片自己确实带一个 click 动作」是上面那条用 `hasClickAction()`
     * 断言的，这一条不重复负责。
     *
     * 标志物取详情页独有的两处，而不是「进去之后 cup 还在」：
     *
     * - 关闭按钮的 contentDescription 只在详情页存在；
     * - 这条夹具没有标题，详情页因此报 [R.string.detail_untitled]，而卡片上的同一位置
     *   走的是 `displayTitle()` 的场景名兜底（「厨房」）。两句不一样，才分得清是**换页**
     *   还是「时间轴上恰好也多了一行字」。
     *
     * 点之前先断言标志物**不在**：少了这一步，这条断言在「详情页一直挂着」的假象下也能过。
     */
    @Test
    fun tappingTheCardOpensTheEntry() {
        compose.waitForIdle()
        val close = compose.activity.getString(R.string.detail_close)
        val untitled = compose.activity.getString(R.string.detail_untitled)
        compose.onNodeWithContentDescription(close).assertDoesNotExist()

        compose.onNodeWithText("cup").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(close).assertIsDisplayed()
        compose.onNodeWithText(untitled).assertIsDisplayed()
    }

    private companion object {
        const val ENTRY_ID = "fixture-cup-entry"

        const val PHOTO_NAME = "fixture-cup.png"

        /** 最小的合法 PNG（1x1 像素）。 */
        const val TINY_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
