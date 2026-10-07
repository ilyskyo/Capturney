// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import android.util.Base64
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * 时间轴的多选要**在真机上长按出来**，而且要能证明四件事各不相干：
 * 卡片自己报得出「我被选中了」、顶栏那句数量跟着走、选中模式下再点另一张是加进来而不是跳页，
 * 以及取消之后点同一张又恢复成跳页。
 *
 * ## 为什么这些都得跑在设备上
 *
 * 状态机本身（`TimelineSelection.selection` 是个 Set）在 JVM 里怎么测都过，但这里每一条坏掉的样子
 * 都不一样，而且都不在集合运算上：
 *
 * - `semantics { selected = ... }` 写在合并过的那个可点节点上还是写在里面的 Text 上——
 *   写错了读屏就念不出「已选中」，而屏幕上完全看不出差别（#33 补的就是这一条，补完一直没验过）。
 * - 「选中模式里点卡片是切换而不是打开详情」这一条靠 `if (selection.active)` 分流，
 *   分流写反的话用户是在选第 3 张，结果被丢进一张详情页，选择整堆作废——
 *   而这在静态代码上看是一行正常的 if。
 * - 取消之后要**真的退出**这个模式：只把数量读数抹掉、`active` 还留着，界面就成了一半多选一半浏览。
 *
 * ## 夹具
 *
 * 两条记录隔一天、词不同（cup / bowl），走的是应用自己的写入路径；照片是 1x1 的合法 PNG，
 * 因为「没有照片的日记不成立」。跑完连同夹具一起抹掉——磁盘由 `PreserveAppFiles` 还原，
 * 内存里那份文档态只能自己 `deleteEntry`。删除键一次都不按：那是用户的数据。
 */
@RunWith(AndroidJUnit4::class)
class TimelineSelectionTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(PreserveAppFiles())
        .around(SeedTwoDays())
        .around(compose)

    private class SeedTwoDays : ExternalResource() {
        override fun before() {
            val container = app().container
            writePhoto(container, PHOTO_NAME)
            val now = System.currentTimeMillis()
            val day = TimeUnit.DAYS.toMillis(1)
            val diary = container.diary
            runBlocking {
                // 先按自己的 id 清一遍再种：整轮跑的时候前一个类可能把同 id 留在**这一进程的内存文档**里
                // （`addEntry` 同 id 只认第一次，于是这里静默地什么都没加，症状是「找不到 cup 那一节」，
                // 而它看起来像是界面少了东西，不是夹具没种进去）。
                FIXTURE_IDS.forEach { diary.deleteEntry(it) }
                // 两条各占一天：同一天会被分进同一组，而多选要跨组走。
                listOf(
                    entry("fixture-select-bowl", "bowl", now - day),
                    entry("fixture-select-cup", "cup", now),
                ).forEach { card ->
                    assertTrue(
                        "夹具没种进去：${card.id} 已经在这份文档里了。" +
                            "整轮跑时前一个类的收尾没跑完，去查那一条",
                        diary.addEntry(card),
                    )
                }
            }
        }

        override fun after() {
            val diary = app().container.diary
            runBlocking {
                FIXTURE_IDS.forEach { diary.deleteEntry(it) }
            }
        }

        private fun entry(id: String, word: String, takenAt: Long) = Entry(
            id = id,
            photoPath = PHOTO_NAME,
            takenAt = takenAt,
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
    }

    @Test
    fun selectingCardsAnnouncesItselfAndCountsItself() {
        assertFalse(
            "一进来就处在选中状态：cup 那张报了 selected",
            isSelected(CUP),
        )
        assertEquals(
            "没有多选时不该有那句数量读数",
            0,
            nodes(countMatcher(1)).size,
        )

        compose.onNode(card(CUP)).performTouchInput { longClick() }
        compose.waitForIdle()

        assertTrue("长按之后 cup 那张没报 selected——读屏用户不知道自己选上了哪一张", isSelected(CUP))
        assertFalse("长按一张之后，另一张（bowl）也报了 selected", isSelected(BOWL))
        assertEquals(
            "顶栏那句数量读数",
            1,
            nodes(countMatcher(1)).size,
        )

        compose.onNode(card(BOWL)).performClick()
        compose.waitForIdle()

        assertTrue("再点一张之后 bowl 没报 selected", isSelected(BOWL))
        assertEquals(
            "选了两张之后数量读数应当说 2",
            1,
            nodes(countMatcher(2)).size,
        )
    }

    /**
     * 选中模式里点第三张不许跳进详情页；取消之后点同一张又必须跳进去。
     *
     * 这一条把 `if (selection.active)` 那个分流的两端都量到了：只测「长按能选上」的话，
     * 分流写反（选中模式下点击仍然打开详情）会一路绿到用户真的丢了一堆选择。
     */
    @Test
    fun tapsToggleInsideSelectionModeAndNavigateAgainAfterCancelling() {
        val close = str(R.string.detail_close)
        compose.onNode(card(CUP)).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNode(card(BOWL)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(close).assertDoesNotExist()
        assertTrue("点了另一张之后 bowl 没被选上", isSelected(BOWL))

        compose.onNodeWithText(str(R.string.selection_cancel)).performClick()
        compose.waitForIdle()
        assertFalse("取消之后 cup 还报着 selected——模式没真的退出", isSelected(CUP))
        assertEquals(
            "取消之后那句数量读数还在",
            0,
            nodes(countMatcher(2)).size + nodes(countMatcher(1)).size,
        )

        // 取消之后同一张卡应当回到「点开看详情」那条路上。
        compose.onNode(card(CUP)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(close).assertIsDisplayed()
    }

    private fun isSelected(term: String): Boolean =
        nodes(card(term)).single().config.getOrNull(SemanticsProperties.Selected) == true

    /** 那张卡：可点，而且正反面之一写着夹具里的词。 */
    private fun card(term: String) = SemanticsMatcher("文本是「$term」") { node ->
        node.texts().contains(term)
    } and hasClickAction()

    private fun countMatcher(count: Int) = SemanticsMatcher("数量读数是 $count") { node ->
        node.texts().contains(str(R.string.selection_count, count))
    }

    private fun nodes(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes()

    private fun str(res: Int, vararg args: Any) = compose.activity.getString(res, *args)

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private companion object {
        const val CUP = "cup"
        const val BOWL = "bowl"
        const val PHOTO_NAME = "fixture-select.png"

        val FIXTURE_IDS = listOf("fixture-select-cup", "fixture-select-bowl")

        /** 最小的合法 PNG（1x1 像素）。 */
        const val TINY_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

        fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as CapturneyApplication
    }
}
